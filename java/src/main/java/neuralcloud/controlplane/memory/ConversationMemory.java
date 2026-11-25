package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话记忆读取门面，为各模型节点（Chat Worker、Task Worker、Master）提供按轮次隔离的只读取证入口。
 * 职责：
 * 1. 通过 {@link #bind} 为单轮节点调用签发 {@link MemoryCall} 句柄，避免节点直接接触底层记忆客户端；
 * 2. 兼容 Memory v1（{@code recent} 审计回读与 {@code scoped} 检索）与 Memory v2（带快照水位、冲突/残缺评估与证据绑定）；
 * 3. 将每次召回命中的记录 ID、快照水位及评估状态写入审计链（{@code memory_recall}），保证模型所见与审计记录一致。
 */
public final class ConversationMemory {
    /** v1 recent 模式下最多从本地审计回执回读的近期对话条数。 */
    private static final int RECENT_LIMIT = 6;
    private static final String DURABLE = "memory_durable";
    private final AuditLog audit;
    private final ResidentMemory memory;

    public ConversationMemory(AuditLog audit, ResidentMemory memory) {
        this.audit = audit;
        this.memory = memory;
    }

    /** 为指定节点在当前会话轮次上绑定一个只读记忆调用句柄，并以 {@code parent} 作为审计因果父节点。 */
    public MemoryCall bind(Trace trace, MemoryCall.Caller caller, int parent) {
        return new MemoryCall(this, trace, caller, parent, false);
    }

    /** 为 Master 的意图签发当轮句柄，只有 v2 的授权读取快照能把模型序号还原为确切的记录版本。 */
    public Optional<MemoryCall> bindMasterIntent(Trace trace, int parent) {
        return v2() == null ? Optional.empty()
                : Optional.of(new MemoryCall(this, trace, MemoryCall.Caller.MASTER, parent, true));
    }

    /**
     * 单次记忆召回结果封装。
     * {@code matchCount} 只统计本次检索实际命中的记录数；近期对话回读说明或冲突提示虽会放入 {@code messages}
     * 供 Worker 参考，但不计入命中数。v2 模式下 {@code bindings} 携带每条在线证据的快照绑定供后续复核。
     */
    record Recall(
            List<String> messages, int matchCount, int eventId, List<MemoryJournal.Read> bindings) {
        Recall(List<String> messages, int count, int event) {
            this(messages, count, event, List.of());
        }
    }

    /** 若底层配置为 Memory v2 则返回 {@link ResidentV2} 实例，否则返回 null（表示走 v1 路径）。 */
    ResidentV2 v2() {
        return memory instanceof ResidentV2 v ? v : null;
    }

    /**
     * 统一执行记忆查询并记录 {@code memory_recall} 审计事件。
     * v1 直接分派到 {@link #recall} / {@link #identity} / {@link #selfHistory}；
     * v2 通过 {@link ResidentV2#lookup} 获取一致快照，过滤掉非 {@code ONLINE} 条目，
     * 并在服务端评估为 {@code CONFLICT} 或 {@code INCOMPLETE} 时在结果头部插入明确的风险提示。
     */
    Recall retrieve(Trace trace, int event, QueryKind kind, String query, String role, String input,
                    boolean numberEvidence)
            throws IOException, InterruptedException {
        ResidentV2 v = v2();
        if (v == null)
            return switch (kind) {
                case HISTORY -> recall(trace, event, query);
                case SELF_IDENTITY -> identity(trace, event);
                case SELF_HISTORY -> selfHistory(trace, event, query);
            };
        var snap = v.lookup(trace.scope(), role, kind, query, receivedAt(trace));
        var messages = new ArrayList<String>();
        var rows = new ArrayList<MemoryJournal.Read>();
        // 仅保留当前仍在线的条目生成证据绑定与自然语言呈现，避免把已撤回或离线记录交给模型。
        for (var item : snap.items()) {
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))) continue;
            rows.add(ResidentV2.binding(trace.scope(), role, kind.name(), item, snap, input));
            // 序号只属于实际交给模型的记录。后面插入的冲突、空结果等说明不会占用或移动序号。
            String sentence = ResidentV2.render(item, kind.name());
            messages.add(numberEvidence ? "【证据 " + rows.size() + "】" + sentence : sentence);
        }
        int count = rows.size();
        // recent 模式下的 HISTORY 查询本质上是回读当前会话近期对话，并非按话题检索，因此命中计数归零。
        if (!v.scopedRecall() && kind == QueryKind.HISTORY) {
            messages.addFirst("本次只回读了当前会话的近期对话，共 " + count + " 条。");
            count = 0;
        }
        // 当记忆服务识别到多源证据冲突或缺乏足够回执时，在文本最前提醒模型谨慎判断。
        if (snap.assessment().equals("CONFLICT")) messages.addFirst("当前证据存在冲突。");
        if (snap.assessment().equals("INCOMPLETE")) messages.addFirst("目前依据不足以确认执行结果。");
        int logged =
                trace.write(
                        event,
                        "memory",
                        "memory_recall",
                        Map.of(
                                "recordIds",
                                rows.stream().map(MemoryJournal.Read::id).toList(),
                                "bindings",
                                rows,
                                "stream_id",
                                snap.stream(),
                                "read_seq",
                                snap.seq(),
                                "assessment",
                                snap.assessment()));
        return new Recall(List.copyOf(messages), count, logged, List.copyOf(rows));
    }

    /** 记忆工具支持的三种只读查询语义：当前会话历史、当前用户身份、当前 QQ 本人跨会话发言。 */
    public enum QueryKind { HISTORY, SELF_IDENTITY, SELF_HISTORY }

    /** 仅在启用 scoped 分域记忆且当前请求来自已认证 QQ 私聊时，才开放本人跨会话历史检索。 */
    public boolean canSearchSelfHistory(Trace.Scope scope) {
        return memory.scopedRecall() && scope.qq().isPresent();
    }

    /** v1 路径：查询当前 QQ 账号经操作者确认的称呼，并写入审计链。 */
    Recall identity(Trace trace, int causedBy) throws IOException, InterruptedException {
        var match = memory.selfIdentity(trace.scope());
        var messages = match.stream().map(ResidentMemory.Match::message).toList();
        int event = trace.write(causedBy, "memory", "memory_recall",
                Map.of("recordIds", match.stream().map(ResidentMemory.Match::recordId).toList()));
        return new Recall(messages, messages.size(), event);
    }

    /** v1 路径：按当前私聊对方 QQ 号检索其本人在本轮消息到达前的跨会话发言节选。 */
    Recall selfHistory(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
        var matches = memory.selfHistory(trace.scope().qq().orElseThrow(), text, receivedAt(trace));
        return historyResult(trace, causedBy, matches);
    }

    /**
     * v1 路径：执行会话历史查询。
     * - {@code scoped} 模式直接调用记忆服务按会话与时间上界检索；
     * - {@code recent} 模式从本地审计链中筛选本轮请求之前、属于当前会话且角色匹配的最近 {@value #RECENT_LIMIT} 条
     *   {@code memory_durable} 回执，再按时间正序回读正文（不计为历史检索命中）。
     */
    Recall recall(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
        var scope = trace.scope();
        if (memory.scopedRecall()) {
            return historyResult(trace, causedBy, memory.search(scope, text, receivedAt(trace)));
        }

        // recent 模式按持久回执重读近期对话，只提供上下文，话题命中计数为零。
        // 严格过滤掉本轮请求及之后的事件（同一 runId 下 eventId >= trace.request()），防止把刚写入的本轮消息当成历史。
        var records = audit.events(scope.chain()).reversed().stream()
                .filter(event -> DURABLE.equals(event.type()))
                .filter(event -> !scope.runId().equals(event.runId()) || event.eventId() < trace.request())
                .map(event -> event.data().getAsJsonObject())
                .filter(event -> scope.sessionId().equals(event.get("sessionId").getAsString()))
                .filter(memory::acceptsReceipt)
                .map(event -> event.get("recordId").getAsString())
                .distinct().limit(RECENT_LIMIT).toList();
        var recent = new LinkedHashMap<String, String>();
        for (String id : records.reversed()) {
            memory.read(scope, id).ifPresent(message -> recent.put(id, message));
        }
        int event = trace.write(causedBy, "memory", "memory_recall",
                Map.of("recordIds", List.copyOf(recent.keySet()), "historicalRecordIds", List.of()));
        var materials = new ArrayList<String>();
        materials.add("本次只回读了当前会话的近期对话，共 " + recent.size() + " 条。");
        materials.addAll(recent.values());
        return new Recall(List.copyOf(materials), 0, event);
    }

    /** 读取本轮入站请求在审计链中的落盘时间，作为历史检索的严格时间上界，排除当前及未来发言。 */
    private Instant receivedAt(Trace trace) {
        return Instant.parse(audit.event(trace.scope(), trace.request()).recordedAt());
    }

    /** 汇总 v1 检索结果并写入 {@code memory_recall} 审计事件，确保去重顺序与记录 ID 列表严格对应。 */
    private static Recall historyResult(Trace trace, int causedBy, List<ResidentMemory.Match> found) throws IOException {
        // 两种范围都只将本轮的实际命中记入回执，样板句与记录 ID 按相同顺序对应。
        var matches = new LinkedHashMap<String, String>();
        for (var match : found) {
            matches.put(match.recordId(), match.message());
        }
        var ids = List.copyOf(matches.keySet());
        int event = trace.write(causedBy, "memory", "memory_recall", Map.of("recordIds", ids, "historicalRecordIds", ids));
        return new Recall(List.copyOf(matches.values()), ids.size(), event);
    }

}
