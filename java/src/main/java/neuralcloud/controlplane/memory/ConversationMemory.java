package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.worker.DialogueMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** 启动握手结束后，只有已协商 HISTORY/CITE 的读取端才能参与自动查证。 */
    public boolean supportsAutomaticRecall() {
        return v2() != null && v2().supportsAudienceReads();
    }

    /** 消息保持各自的说话者。另行保留实际进入窗口的版本，供后续模型调用和发送前复核。 */
    public record Dialogue(List<DialogueMessage> messages, Map<Trace.MemoryTarget, Long> versions) {}

    private static boolean replayable(com.google.gson.JsonObject item) throws IOException {
        return "ONLINE".equals(ResidentV2.string(item, "status"))
                && (!item.has("evidence_active") || item.get("evidence_active").getAsBoolean());
    }

    /** 撤回可能发生在第一次规划之后。只要已送入模型的历史发生变化，本轮旧稿就不能继续生成或送达。 */
    void verifyDialogue(Trace.Scope scope, Map<Trace.MemoryTarget, Long> versions)
            throws IOException, InterruptedException {
        var v = v2();
        var current = v.reread(scope, "CHAT_WORKER", versions.keySet());
        for (var entry : current.entrySet()) {
            var item = entry.getValue().item();
            if (!v.stream().equals(entry.getValue().stream()) || !replayable(item)
                    || ResidentV2.integer(item.getAsJsonObject("ref"), "revision", 1) != versions.get(entry.getKey()))
                throw new IOException("本轮使用的对话历史已更正或不可引用，需要重新查证");
        }
    }

    /** 近期对话按当前受众批量回读。未取得回执的写入只按原幂等键对账，旧版或失效的正文不得重新拼入。 */
    public Dialogue dialogue(Trace trace) throws IOException, InterruptedException {
        var v = v2();
        if (v == null) return new Dialogue(trace.dialogue(), Map.of());
        var scope = trace.scope();
        var rows = audit.historyBefore(scope.channel(), scope.chain(), trace.request(), true).reversed();
        var result = new ArrayList<DialogueMessage>();
        var versions = new LinkedHashMap<Trace.MemoryTarget, Long>();
        var covered = new LinkedHashSet<MemoryCall.EvidenceRef>();
        int remaining = AuditLog.DIALOGUE_CHAR_BUDGET;
        history:
        for (int first = 0; first < rows.size(); first += 128) {
            var batch = new ArrayList<>(rows.subList(first, Math.min(first + 128, rows.size())));
            if (!v.supportsAudienceReads()) batch.removeIf(AuditLog.HistoryEntry::sourced);
            var targets = new LinkedHashSet<Trace.MemoryTarget>();
            for (int i = 0; i < batch.size(); i++) {
                var row = batch.get(i);
                if (row.writeRequested() && row.recordId() == null) {
                    var receipt = v.observationReceipt(row.eventKey());
                    if (receipt.isEmpty()) continue;
                    var data = receipt.get();
                    row = row.withReceipt(
                            ResidentV2.string(data, "record_id"), ResidentV2.integer(data, "revision", 1));
                    batch.set(i, row);
                }
                if (row.recordId() != null) targets.add(new Trace.MemoryTarget("HISTORY", row.recordId()));
            }
            var current = v.reread(scope, "CHAT_WORKER", targets);
            for (var row : batch) {
                var message = row.message();
                if (row.writeRequested() && row.recordId() == null) continue;
                var target = row.recordId() == null ? null : new Trace.MemoryTarget("HISTORY", row.recordId());
                var item = target == null ? null : current.get(target).item();
                long revision = 0;
                if (item != null) {
                    if (!replayable(item)) continue;
                    revision = ResidentV2.integer(item.getAsJsonObject("ref"), "revision", 1);
                    // 有来源的送达只采用当前受众获准的自述正文。未绑定来源的旧写入不能证明引用权。
                    if (row.sourced() && (!"self_expression".equals(ResidentV2.string(item, "kind"))
                            || item.getAsJsonArray("source_refs").contains(item.get("ref")))) continue;
                    if (row.sourced() || revision != row.revision()) {
                        String text = v.replacementText(scope, item);
                        message = new DialogueMessage(message.speaker(), text);
                    }
                }
                if (message.text().length() > remaining) break history;
                result.add(message);
                if (target != null) versions.put(target, revision);
                if (row.sourced()) {
                    // 回执引用上一条自述，使撤权和更正也约束新回复。新自述已依赖的旧版本无需再次列为直接来源。
                    if (!covered.contains(new MemoryCall.EvidenceRef(row.recordId(), revision)))
                        trace.sharedSources().merge(target, revision, Math::min);
                    if (revision == row.revision()) covered.addAll(row.directSources());
                    if (item.has("evidence_valid_until"))
                        trace.validUntil(Instant.parse(ResidentV2.string(item, "evidence_valid_until")));
                }
                remaining -= message.text().length();
            }
        }
        return new Dialogue(List.copyOf(result.reversed()), Map.copyOf(versions));
    }

    /**
     * 统一执行记忆查询并记录 {@code memory_recall} 审计事件。
     * v1 直接分派到 {@link #recall} / {@link #identity} / {@link #selfHistory}；
     * v2 通过 {@link ResidentV2#lookup} 获取一致快照，过滤掉非 {@code ONLINE} 条目，
     * 并在服务端评估为 {@code CONFLICT} 或 {@code INCOMPLETE} 时在结果头部插入明确的风险提示。
     */
    Recall retrieve(Trace trace, int event, QueryKind kind, String query, String role, String input,
                    boolean numberEvidence, int byteBudget)
            throws IOException, InterruptedException {
        ResidentV2 v = v2();
        if (v == null)
            return switch (kind) {
                case HISTORY -> recall(trace, event, query);
                case SELF_IDENTITY -> identity(trace, event);
                case SELF_HISTORY -> selfHistory(trace, event, query);
                case IDENTITY -> throw new IOException("身份目录需要 Memory v2 的 identity_lookup 能力");
            };
        var snap = v.lookup(trace.scope(), role, kind, query, receivedAt(trace));
        var messages = new ArrayList<String>();
        var rows = new ArrayList<MemoryJournal.Read>();
        String qualification = switch (snap.assessment()) {
            case "CONFLICT" -> "当前证据存在冲突。";
            case "INCOMPLETE" -> "目前依据不足以确认执行结果。";
            default -> "";
        };
        if (!qualification.isEmpty()) messages.add(qualification);
        int bytes = qualification.getBytes(StandardCharsets.UTF_8).length;
        // 仅保留当前仍在线的条目生成证据绑定与自然语言呈现，避免把已撤回或离线记录交给模型。
        for (var item : snap.items()) {
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))) continue;
            String sentence = ResidentV2.render(item, kind.name());
            if (numberEvidence) sentence = "【证据 " + (rows.size() + 1) + "】" + sentence;
            int size = sentence.getBytes(StandardCharsets.UTF_8).length + (messages.isEmpty() ? 0 : 1);
            if (size > byteBudget - bytes) continue;
            bytes += size;
            messages.add(sentence);
            rows.add(ResidentV2.binding(trace.scope(), role, kind.name(), item, snap, input));
        }
        int count = rows.size();
        // recent 模式下的 HISTORY 查询本质上是回读当前会话近期对话，并非按话题检索，因此命中计数归零。
        if (!v.scopedRecall() && kind == QueryKind.HISTORY) {
            messages.addFirst("本次只回读了当前会话的近期对话，共 " + count + " 条。");
            count = 0;
        }
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

    /** IDENTITY 查指定账号的确认资料；SELF_IDENTITY 与 SELF_HISTORY 通过渠道绑定发件人。 */
    public enum QueryKind { HISTORY, SELF_IDENTITY, SELF_HISTORY, IDENTITY }

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
