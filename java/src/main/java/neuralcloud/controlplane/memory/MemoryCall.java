package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.annotations.SerializedName;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;
import neuralcloud.controlplane.worker.DialogueMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 单次节点处理轮次持有的只读记忆访问与取证生命周期句柄。
 * 设计边界：
 * - 调用方角色（{@link Caller}）、会话作用域与渠道本人身份全部由宿主与渠道在构造时绑定，
 *   模型只能通过工具提交 {@code kind} 与 {@code query}，无法伪造账号或跨会话越权；
 * - 除常规按需查询（{@link #read}）外，在 Memory v2 下还协同管理：
 *   1. 轮次起始的变更刷新（{@link #startTurn} / {@link #refreshed}）；
 *   2. Master 专用的受众热目录卡片组装与精确分词预算裁剪（{@link #hotIndex}）；
 *   3. 控制台 Master 专用的印象修订证据核验与落盘（{@link #impressionEvidence} / {@link #saveImpression}）；
 *   4. 跨会话引用来源登记与可选的轮内复读（{@link #registerDeliverySources} / {@link #reviewChatEvidence}）。
 */
public final class MemoryCall {
    /** 暴露给模型调用的函数工具名。 */
    public static final String NAME = "memory_call";
    public static final int AUTOMATIC_INPUT_BYTES = 8192;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    /**
     * 拦截“上文 / 刚才的内容 / 聊天记录”等空泛占位查询词的正则。
     * 历史检索要求提供具体话题关键词，避免模型把空泛词送进召回引擎。
     */
    private static final Pattern GENERIC_HISTORY = Pattern.compile(
            "\\s*(?:上下文|(?:上文|前文)(?:内容)?|(?:刚才|之前|此前|上次|以上)(?:的)?(?:内容|对话|聊天|话题|信息)"
                    + "|历史(?:记录|对话|内容)|聊天记录)[\\p{P}\\s]*");

    /** 允许持有记忆读取句柄的控制面节点类型，对应审计链中的节点标识。 */
    public enum Caller {
        CHAT_WORKER("chat-worker"), TASK_WORKER("task-worker"), MASTER("master");

        private final String node;

        Caller(String node) {
            this.node = node;
        }
    }

    /** 单次记忆工具查询的结构化结果，供模型消费并原样写入 {@code memory_call_result} 审计事件。 */
    public record Result(QueryKind kind, int count, List<String> sentences) {}

    /** 一轮内对同一称呼的解析结果。id 为空时不执行按作者检索。 */
    public record Author(String reference, String id, boolean ambiguous) {
        public String clarification() {
            return ambiguous ? "有多个账号可能对应「" + reference + "」，你指的是哪一位？可以告诉我 QQ 号。"
                    : "我还没找到「" + reference + "」对应的账号，可以告诉我 QQ 号或更具体的称呼吗？";
        }
    }

    private Map<String, Author> authors = new LinkedHashMap<>();

    /** 可交给下游的精确证据引用，记录身份和修订号由 Memory 读取快照提供。 */
    public record EvidenceRef(@SerializedName("record_id") String recordId, long revision) {
        private static EvidenceRef from(MemoryJournal.Read read) {
            return new EvidenceRef(read.id(), read.item().getAsJsonObject("ref").get("revision").getAsLong());
        }
    }

    private final ConversationMemory memory;
    private final Trace trace;
    private final Caller caller;
    private final boolean numberEvidence;
    /** 追踪当前审计因果链上最新一个记忆相关事件 ID，供后续步骤挂接 causedBy。 */
    private int eventId;
    /** 本次句柄对应的唯一输入批次 ID，用于关联本地 SQLite 日志中的 inputs / input_evidence / watches。 */
    private final String inputId = java.util.UUID.randomUUID().toString();
    /** 本轮常规召回或印象修订收集到的在线证据快照绑定。 */
    private List<MemoryJournal.Read> bindings = List.of();
    private boolean preparedRead;
    private Map<Trace.MemoryTarget, Long> dialogueVersions = Map.of();
    /** 本轮起始阶段拉取到的变更刷新结果，用于模型第一轮调用后推进 watches 游标。 */
    private ResidentV2.Refresh refresh;

    /** 本轮 Master 注入的热目录卡片对应的在线证据绑定。 */
    private List<MemoryJournal.Read> hotBindings = List.of();
    /** 正在执行 {@code /memory revise} 时锁定的旧印象引用（record_id + revision）。 */
    private JsonObject previousImpression;

    /** 精确分词器回调接口：由 Master 接入推理端点 {@code /tokenize} 实现。 */
    @FunctionalInterface
    public interface TokenCounter {
        int count(String block) throws IOException, InterruptedException;
    }

    /**
     * 为 Master 轮次构造并校验“历史卡目录”热索引文本块（仅 Memory v2 且配置了 {@code hotIndexTokens > 0} 时启用）。
     * 核心流程：
     * 1. 从 {@code v2/cards} 拉取最多 12 张 L0 卡片指针，逐条拼接并用真实分词器核算 token 数，严格控制在预算以内；
     * 2. 分词器不可用时写入 {@code memory_hot_index_unavailable} 审计事件并安全退回空块，不猜测 token 数；
     * 3. 通过 {@code v2/read} 二次读取选中卡片的实时在线状态与断言活性，并重查 {@code v2/cards} 确认计数期间未被撤回/重建；
     * 4. 登记不可变审计证据（{@code prepareHot}）及交付来源检查，确保这里被计数的字符串与送给 Master 的文本完全一致。
     */
    public String hotIndex(TokenCounter tokenizer) throws IOException, InterruptedException {
        var v = memory.v2();
        if (caller != Caller.MASTER || v == null || v.hotIndexTokens == 0) return "";
        var cards = v.cardPointers(trace.scope());
        var selected = new ArrayList<JsonObject>();
        String block = "";
        int total = 0;
        for (var entry : cards) {
            var card = entry.getAsJsonObject();
            var fields = new ArrayList<String>();
            fields.add("OPEN".equals(ResidentV2.string(card, "priority")) ? "未决" : "目录");
            fields.add("SUBJECT".equals(ResidentV2.string(card, "axis")) ? "事项" : "人物");
            fields.add(ResidentV2.string(card, "title"));
            if (card.has("deadline")) fields.add("期限 " + ResidentV2.string(card, "deadline"));
            if (card.has("valid_until")) fields.add("有效至 " + ResidentV2.string(card, "valid_until"));
            String line = JSON.toJson(fields);
            String proposal = (block.isEmpty() ? "历史卡目录" : block) + "\n" + line;
            int count;
            try {
                count = tokenizer.count(proposal + "\n\n");
            } catch (IOException unavailable) {
                trace.write(
                        eventId,
                        "memory",
                        "memory_hot_index_unavailable",
                        Map.of("reason", unavailable.toString()));
                return "";
            }
            if (count <= v.hotIndexTokens) {
                selected.add(card);
                block = proposal;
                total = count;
            }
        }
        if (selected.isEmpty()) return "";
        block += "\n\n";
        // 对预算内选中的卡片回查最新记录状态，防止将失效或非活跃断言注入热目录。
        var ids =
                selected.stream()
                        .map(c -> c.getAsJsonObject("ref").get("record_id").getAsString())
                        .distinct()
                        .toList();
        var now = v.readItems(trace.scope(), caller.name(), "HISTORY", ids);
        var watches = new LinkedHashMap<String, MemoryJournal.Read>();
        for (var item : now.items()) {
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))
                    || (item.has("assertion_active")
                            && !item.get("assertion_active").getAsBoolean())) return "";
            var id = item.getAsJsonObject("ref");
            if (selected.stream().noneMatch(c -> c.get("ref").equals(id))) return "";
            var read =
                    ResidentV2.binding(
                            trace.scope(), caller.name(), "HISTORY", item, now, inputId + "-hot");
            watches.put(read.id(), read);
        }
        // 在远端分词计数期间目录可能被重建或撤权，发送前再复核一次指针集合一致性。
        if (!v.cardPointers(trace.scope()).asList().containsAll(selected)) return "";
        hotBindings = List.copyOf(watches.values());
        v.prepareHot(inputId + "-hot", trace.scope(), block, hotBindings);
        registerDeliverySources(hotBindings);
        eventId =
                trace.write(
                        eventId,
                        "memory",
                        "memory_hot_index",
                        Map.of(
                                "tokens",
                                total,
                                "budget",
                                v.hotIndexTokens,
                                "block",
                                block,
                                "input_id",
                                inputId + "-hot"));
        return block;
    }

    /**
     * 解析并校验 {@code /memory revise <旧印象ID@版本> <原始证据1@版本> ...} 命令所需的证据材料。
     * 安全边界：仅允许正式网页控制台的 Master 在 scoped Memory v2 下触发，禁止历史消息或外部渠道发起写入；
     * 会实时校验旧印象版本未漂移、且全部引用的原始证据均为仍在线且活跃的 {@code direct} 直接来源。
     */
    public String impressionEvidence(String spec) throws IOException, InterruptedException {
        if (memory.v2() == null
                || !memory.v2().scopedRecall()
                || caller != Caller.MASTER
                || !trace.scope().channel().equals("console"))
            throw new IOException("印象修订需要正式控制台 Master 与 scoped Memory v2");
        var refs = new ArrayList<JsonObject>();
        String[] fields = spec.split(" ", -1);
        if (fields.length < 2 || fields.length > 17)
            throw new IOException("需提供旧印象和 1–16 项原始证据 ID@版本");
        for (String field : fields) {
            if (!field.matches("[0-9a-f]{64}@[1-9][0-9]{0,17}"))
                throw new IOException("来源须为记录ID@版本");
            var r =
                    ResidentV2.object(
                            "record_id",
                            field.substring(0, 64),
                            "revision",
                            Long.parseLong(field.substring(65)));
            if (refs.contains(r)) throw new IOException("来源重复");
            refs.add(r);
        }
        var ids = refs.stream().map(r -> r.get("record_id").getAsString()).toList();
        var now = memory.v2().readItems(trace.scope(), caller.name(), "HISTORY", ids);
        // 第一个参数必须是待替换的旧印象，且状态须为 SOURCE_CHANGED 或仍在线的 impression 记录。
        var old = now.items().getFirst();
        previousImpression = refs.getFirst();
        if (!previousImpression.equals(old.get("ref"))
                || !("SOURCE_CHANGED".equals(ResidentV2.string(old, "status"))
                        || ("ONLINE".equals(ResidentV2.string(old, "status"))
                                && "impression".equals(ResidentV2.string(old, "kind")))))
            throw new IOException("旧印象已变化");
        // 其余参数必须全部为版本精确匹配的直接原始证据（direct），防止基于二次派生线索套娃修订。
        var reads = new ArrayList<MemoryJournal.Read>();
        var evidence = new ArrayList<String>();
        for (int i = 1; i < refs.size(); i++) {
            var item = now.items().get(i);
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))
                    || !refs.get(i).equals(item.get("ref"))
                    || !"direct".equals(ResidentV2.string(item, "basis"))
                    || item.has("assertion_active") && !item.get("assertion_active").getAsBoolean())
                throw new IOException("修订须引用仍可见的当前原始证据");
            evidence.add(ResidentV2.render(item, "HISTORY"));
            reads.add(
                    ResidentV2.binding(
                            trace.scope(), caller.name(), "HISTORY", item, now, inputId));
        }
        bindings = List.copyOf(reads);
        memory.v2().prepare(inputId, caller.name(), trace.scope(), evidence, bindings);
        registerDeliverySources(bindings);
        return JSON.toJson(evidence);
    }

    /**
     * 提交 Master 生成的新印象正文：落盘前再次复核全部依赖证据未在生成期间发生变更（{@link #verifyEvidence}），
     * 成功后写入 {@code memory_impression_revised} 审计事件并返回 {@code recordId@revision}。
     */
    public String saveImpression(String text) throws IOException, InterruptedException {
        delivered();
        verifyEvidence();
        var rc =
                memory.v2()
                        .replaceImpression(
                                trace.scope(),
                                trace.scope().runId() + ":" + trace.request() + ":impression",
                                previousImpression,
                                bindings.stream()
                                        .map(b -> b.item().getAsJsonObject("ref"))
                                        .toList(),
                                text);
        eventId =
                trace.write(
                        eventId,
                        "memory",
                        "memory_impression_revised",
                        Map.of(
                                "previous",
                                previousImpression,
                                "receipt",
                                rc,
                                "source_refs",
                                bindings.stream().map(b -> b.item().get("ref")).toList()));
        return rc.recordId() + "@" + rc.revision();
    }

    /**
     * 轮次起始同步：检查当前角色在本会话曾消费过的记忆证据是否发生了修订、撤回或到期，
     * 若存在变更则写入本地日志与 {@code memory_context_reset} 审计事件，并返回需前置注入给模型的更正提示列表。
     */
    public List<String> startTurn() throws IOException, InterruptedException {
        var v = memory.v2();
        if (v == null) return List.of();
        refresh = v.refresh(trace.scope(), caller.name());
        if (!refresh.messages().isEmpty()) {
            v.prepare(
                    inputId + "-refresh",
                    caller.name(),
                    trace.scope(),
                    refresh.messages(),
                    refresh.evidence());
            registerDeliverySources(refresh.evidence());
            eventId =
                    trace.write(
                            eventId,
                            "memory",
                            "memory_context_reset",
                            Map.of(
                                    "materials",
                                    refresh.messages(),
                                    "input_id",
                                    inputId + "-refresh"));
        }
        return refresh.messages();
    }

    /** 确认模型已接收本轮的热目录与更正材料，标记输入已交付并推进本地 {@code watches} 游标。 */
    public void refreshed() throws IOException {
        if (!hotBindings.isEmpty()) memory.v2().delivered(inputId + "-hot");
        if (refresh != null && !refresh.updates().isEmpty())
            memory.v2().finishRefresh(inputId + "-refresh", refresh);
    }

    /** 确认模型已完成消费本轮通过 {@link #read} 或 {@link #impressionEvidence} 注入的证据。 */
    public void delivered() throws IOException {
        if (memory.v2() != null && (preparedRead || !bindings.isEmpty())) memory.v2().delivered(inputId);
    }

    /**
     * 筛选出受时效限制或依赖原作者授权（{@code grant_dependent}）的敏感/共享证据：
     * 记录实际取到的用途、版本与已知截止时间，供交付回执及下轮重放使用。
     * Chat Worker 可单独启用此处的远端复读，其他角色使用原有取证契约。
     */
    private void registerDeliverySources(List<MemoryJournal.Read> evidence)
            throws IOException, InterruptedException {
        var shared = evidence.stream().filter(read -> {
            var item = read.item();
            return item.has("evidence_valid_until")
                    || (item.has("assertion") && item.getAsJsonObject("assertion").has("valid_until"))
                    || (item.has("grant_dependent") && item.get("grant_dependent").getAsBoolean());
        }).toList();
        if (shared.isEmpty()) return;
        if (caller != Caller.CHAT_WORKER || memory.chatReviews().contains(ChatReview.RECALL_SOURCES))
            memory.v2().verifyEvidence(trace.scope(), caller.name(), shared);
        for (var read : shared) {
            long revision = ResidentV2.integer(read.item().getAsJsonObject("ref"), "revision", 1);
            trace.sharedSources().merge(new Trace.MemoryTarget(read.kind(), read.id()), revision, Math::min);
            var item = read.item();
            if (item.has("evidence_active") && item.get("evidence_active").getAsBoolean())
                trace.validUntil(
                        java.time.Instant.parse(ResidentV2.string(item, "evidence_valid_until")));
            if (item.has("assertion_active")
                    && item.get("assertion_active").getAsBoolean()
                    && item.getAsJsonObject("assertion").has("valid_until"))
                trace.validUntil(
                        java.time.Instant.parse(
                                ResidentV2.string(
                                        item.getAsJsonObject("assertion"), "valid_until")));
        }
    }

    /**
     * 在执行外部动作或保存派生印象前，汇总本轮全部已引用证据（常规召回、热目录及在线刷新项）向 Memory v2 复核，
     * 只要任一依据发生版本变化、下线或活性翻转即抛错阻断，迫使重新取证。
     */
    public void verifyEvidence() throws IOException, InterruptedException {
        if (memory.v2() == null) return;
        if (!dialogueVersions.isEmpty()) memory.verifyDialogue(trace.scope(), dialogueVersions);
        var current = new ArrayList<>(bindings);
        current.addAll(hotBindings);
        if (refresh != null)
            for (var r : refresh.evidence())
                if ("ONLINE".equals(ResidentV2.string(r.item(), "status"))) current.add(r);
        memory.v2().verifyEvidence(trace.scope(), caller.name(), current);
    }

    /** 前台用阶段开关决定是否再查本轮依据；现场动作和 Master 仍直接使用 verifyEvidence。 */
    public void reviewChatEvidence(ChatReview stage) throws IOException, InterruptedException {
        if (memory.chatReviews().contains(stage)) verifyEvidence();
    }

    MemoryCall(ConversationMemory memory, Trace trace, Caller caller, int parent, boolean numberEvidence) {
        this.memory = memory;
        this.trace = trace;
        this.caller = caller;
        this.eventId = parent;
        this.numberEvidence = numberEvidence;
    }

    /** 记录实际用于本轮规划的历史版本。追加查证继承同一组历史，不能借新句柄绕过期间发生的撤回。 */
    public List<DialogueMessage> dialogue() throws IOException, InterruptedException {
        var history = memory.dialogue(trace);
        dialogueVersions = history.versions();
        return history.messages();
    }

    public MemoryCall nextRead(int parent) {
        var next = memory.bind(trace, caller, parent);
        next.dialogueVersions = dialogueVersions;
        next.authors = authors;
        return next;
    }

    /** 只以实际交付的同群通知原文窗口校验引用，其他候选材料留在 Master 的受众边界内。 */
    public Map<EvidenceRef, String> noticeEvidence() {
        var notices = new LinkedHashMap<EvidenceRef, String>();
        for (var binding : bindings) {
            var item = binding.item();
            if (item.has("member_notice") && item.get("member_notice").getAsBoolean())
                notices.put(EvidenceRef.from(binding), item.get("text").getAsString());
        }
        return notices;
    }

    /** Worker 筛选完跨会话通知后，保留其他命中和检索范围说明，未选通知不进入最终回复输入。 */
    public List<String> withoutNoticeTexts(List<String> sentences) throws IOException {
        var remaining = new ArrayList<>(sentences);
        for (var read : bindings) {
            var item = read.item();
            if (item.has("member_notice") && item.get("member_notice").getAsBoolean())
                remaining.remove(ResidentV2.render(item, read.kind()));
        }
        return remaining;
    }

    /** 顺序与当轮记忆工具正文的证据编号一致。热目录和刷新通知由各自的交付记录管理。 */
    public List<EvidenceRef> evidenceRefs() throws IOException {
        if (!numberEvidence) throw new IOException("submit_intent 只能引用意图轮次实际交付的编号");
        return bindings.stream().map(EvidenceRef::from).toList();
    }

    /** 只向回复节点传递本轮私聊按发件人查得、且被 Master 按确切修订引用的称呼。 */
    public Optional<String> confirmedSenderName(List<EvidenceRef> cited) {
        if (trace.scope().qq().isEmpty()) return Optional.empty();
        return bindings.stream()
                .filter(read -> QueryKind.SELF_IDENTITY.name().equals(read.kind()) && cited.contains(EvidenceRef.from(read)))
                .map(read -> read.item().get("text").getAsString())
                .findFirst();
    }

    /** 审计按输入标识关联规划实际消费的材料、字节数和 SQLite 中的确切证据版本。 */
    public Map<String, Object> consumedInput(Result result) {
        return Map.of("inputId", inputId, "kind", result.kind(), "count", result.count(),
                "utf8Bytes", String.join("\n", result.sentences()).getBytes(StandardCharsets.UTF_8).length);
    }

    /** 返回本句柄最近一次写入的记忆审计事件 ID，供调用方串联下一事件的 causedBy。 */
    public int eventId() {
        return eventId;
    }

    /**
     * 校验查询词是否符合契约：
     * 历史查询需要具体关键词；目录接受空串列举或精确查询；本人身份和非查询规划均使用空串。
     */
    public static boolean validQuery(String kind, String query) {
        if (kind.equals("IDENTITY"))
            return query.isEmpty() || (!query.isBlank() && query.codePointCount(0, query.length()) <= 256);
        return validQuery(kind.equals("HISTORY") || kind.equals("SELF_HISTORY"), query);
    }

    /** 只有带合法作者的 HISTORY 可以省略话题；QQ 号与 Memory 的渠道类型共用相同范围。 */
    public static boolean validQuery(String kind, String query, String authorRef) {
        if (authorRef == null) return validQuery(kind, query);
        return kind.equals("HISTORY") && !authorRef.isBlank() && authorRef.equals(authorRef.strip())
                && authorRef.codePointCount(0, authorRef.length()) <= 256
                && authorRef.codePoints().noneMatch(Character::isISOControl)
                && (!authorRef.matches("[0-9]+") || authorRef.matches("[1-9][0-9]{4,15}"))
                && (query.isEmpty() || validQuery(true, query));
    }

    /** 既有调用方区分历史关键词与本人称呼的空查询。 */
    public static boolean validQuery(boolean history, String query) {
        return history ? !query.isBlank() && query.codePointCount(0, query.length()) <= 256
                && !GENERIC_HISTORY.matcher(query).matches() : query.isEmpty();
    }

    /** 规划、工具和执行共用同一能力列表。身份目录可面向群与私聊，本人专用查询仍绑定渠道。 */
    public List<QueryKind> queryKinds() {
        var kinds = new ArrayList<>(List.of(QueryKind.HISTORY));
        if (trace.scope().group().isEmpty()) kinds.add(QueryKind.SELF_IDENTITY);
        if (memory.canSearchSelfHistory(trace.scope())) kinds.add(QueryKind.SELF_HISTORY);
        if (memory.v2() != null && memory.v2().identityLookup()) kinds.add(QueryKind.IDENTITY);
        return List.copyOf(kinds);
    }

    /** 规划、原生工具和执行只在同一私聊受众与协商能力下开放作者条件。 */
    public boolean authorHistory() {
        return trace.scope().qq().isPresent() && memory.v2() != null && memory.v2().supportsQqAuthorHistory();
    }

    /** 确认目录优先，零命中才查本轮共同群的精确昵称和名片；网络错误由渠道报告。 */
    public Author resolveAuthor(String ref) throws IOException, InterruptedException {
        if (ref == null) return null;
        if (!authorHistory() || !validQuery("HISTORY", "", ref))
            throw new IOException("author_ref 需要支持作者查询的 QQ 私聊及有效账号或完整称呼");
        if (authors.containsKey(ref)) return authors.get(ref);
        long started = System.nanoTime();
        var accounts = new java.util.LinkedHashSet<String>();
        boolean conflict = false;
        String source = "qq_id";
        try {
            if (ref.matches("[1-9][0-9]{4,15}")) {
                accounts.add(ref);
            } else {
                source = "confirmed_identity";
                var v = memory.v2();
                if (v.identityLookup()) {
                    var directory = v.lookup(trace.scope(), caller.name(), QueryKind.IDENTITY, ref, memory.receivedAt(trace));
                    conflict = directory.assessment().equals("CONFLICT");
                    for (var item : directory.items())
                        accounts.add(ResidentV2.string(item, "identity_account_id"));
                    if (!conflict && accounts.size() == 1) {
                        // 由确认目录绑定的称呼沿用 IDENTITY 的来源用途，分享策略回切时可回收派生回复。
                        var record = directory.items().getFirst().getAsJsonObject("ref");
                        trace.sharedSources().put(new Trace.MemoryTarget("IDENTITY", ResidentV2.string(record, "record_id")),
                                ResidentV2.integer(record, "revision", 1));
                    }
                }
                if (!conflict && accounts.isEmpty()) {
                    source = "group_members";
                    accounts.addAll(trace.memberAccounts(ref));
                }
            }
        } catch (IOException | InterruptedException failure) {
            trace.write(eventId, "router", "qq_author_lookup_failed", Map.of("author_ref", ref, "source", source,
                    "detail", failure.toString()));
            throw failure;
        }
        boolean ambiguous = conflict || accounts.size() > 1;
        String id = !ambiguous && accounts.size() == 1 ? accounts.getFirst() : null;
        var author = new Author(ref, id, ambiguous);
        authors.put(ref, author);
        var audit = new LinkedHashMap<String, Object>();
        audit.put("author_ref", ref);
        audit.put("source", source);
        audit.put("status", ambiguous ? "AMBIGUOUS" : id == null ? "NOT_FOUND" : "RESOLVED");
        if (id != null) audit.put("qq_sender_id", id);
        audit.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
        eventId = trace.write(eventId, "router", "qq_author_lookup", audit);
        return author;
    }

    /** 补查优先。相同材料只交付一次，规划时已消费的预查材料仅在本次剩余预算内重用。 */
    public static List<String> mergeMaterials(List<String> result, List<String> earlier) {
        if (earlier.isEmpty()) return result;
        var messages = new java.util.LinkedHashSet<>(result);
        int used = String.join("\n", messages).getBytes(StandardCharsets.UTF_8).length;
        for (String line : earlier) {
            int needed = line.getBytes(StandardCharsets.UTF_8).length + (messages.isEmpty() ? 0 : 1);
            if (needed <= AUTOMATIC_INPUT_BYTES - used && messages.add(line)) used += needed;
        }
        return List.copyOf(messages);
    }

    /** 空 query 表示查本人、或按 IDENTITY 列举有确认记录的账号。 */
    public Map<String, Object> tool() {
        var kinds = queryKinds();
        boolean identity = kinds.contains(QueryKind.SELF_IDENTITY);
        boolean directory = kinds.contains(QueryKind.IDENTITY);
        String description =
                "HISTORY 查可在当前会话引用的历史材料，query 填具体话题关键词。"
                        + (identity ? "SELF_IDENTITY 仅查当前发言者本人已确认的称呼，query 填空字符串。角色自身资料由角色设定提供。" : "")
                        + (directory ? "IDENTITY 查任一账号的已确认称呼，query 填 QQ 号或完整称呼，空串列举。按结果中的账号归属引用。" : "")
                        + (kinds.contains(QueryKind.SELF_HISTORY) ? "SELF_HISTORY 按话题关键词跨会话查当前 QQ 发件人以往的原话。" : "");
        var fields = new LinkedHashMap<String, Object>();
        fields.put("kind", Map.of("type", "string", "enum", kinds));
        fields.put("query", Map.of("type", "string", "minLength", identity || directory ? 0 : 1, "maxLength", 256));
        if (authorHistory()) {
            description += "查指定 QQ 发言者的原话用 HISTORY，author_ref 填 QQ 号或完整称呼；query 可为空或填具体话题。";
            fields.put("author_ref", Map.of("type", "string", "minLength", 1, "maxLength", 256));
        }
        return Map.of("type", "function", "function", Map.of("name", NAME, "description", description,
                "parameters", Map.of("type", "object", "additionalProperties", false, "required", List.of("kind", "query"),
                        "properties", fields)));
    }

    /**
     * 执行一次受审计的只读记忆查询：
     * 1. 先按渠道边界校验 {@code kind} 与 {@code query}，并写入 {@code memory_call} 请求审计事件；
     * 2. 调用 {@link ConversationMemory#retrieve} 取回材料；无命中时返回明确的自然语言空结果说明；
     * 3. v2 下登记本地 {@code inputs}/{@code watches} 并完成交付来源校验，最后写出 {@code memory_call_result} 审计事件。
     */
    public Result read(QueryKind kind, String query) throws IOException, InterruptedException {
        return read(kind, query, Integer.MAX_VALUE);
    }

    /** 预算只计交给消费者的完整证据句和行间换行，按同一批实际交付项登记版本。 */
    public Result read(QueryKind kind, String query, int byteBudget) throws IOException, InterruptedException {
        return read(kind, query, byteBudget, null);
    }

    /** 同一解析结果用于正式检索、查询审计及预查复用。未能唯一定位时交付澄清信息。 */
    public Result read(QueryKind kind, String query, int byteBudget, Author author) throws IOException, InterruptedException {
        if (!queryKinds().contains(kind) || !validQuery(kind.name(), query, author == null ? null : author.reference())
                || author != null && !authorHistory())
            throw new IOException("memory_call 的 kind 或 query 不符合当前渠道的查询契约（含可选 author_ref）");
        var args = new LinkedHashMap<String, Object>();
        args.put("kind", kind.name());
        args.put("query", query);
        if (author != null) args.put("author_ref", author.reference());
        if (author != null && author.id() != null) args.put("qq_sender_id", author.id());
        int requested = trace.write(eventId, caller.node, NAME, args);
        if (author != null && author.id() == null) {
            bindings = List.of();
            preparedRead = false;
            var result = new Result(kind, 0, List.of(author.clarification()));
            eventId = trace.write(requested, "memory", "memory_call_result", result);
            return result;
        }
        ConversationMemory.Recall recalled;
        try {
            recalled = memory.retrieve(trace, requested, kind, query, caller.name(), inputId, numberEvidence, byteBudget, author);
        } catch (IOException | InterruptedException error) {
            trace.write(requested, "memory", "memory_call_failed", Map.of("detail", error.toString()));
            throw error;
        }
        var sentences = recalled.messages();
        if (sentences.isEmpty()) {
            sentences = List.of(switch (kind) {
                case HISTORY -> author != null ? "未查到「" + author.reference() + "」在本次受众范围内符合条件的原话。"
                        : memory.v2() == null ? "当前会话中未查到符合条件的历史记录。"
                        : "未查到当前会话可引用的相关历史记录。";
                case SELF_IDENTITY -> "没有查到当前发信用户经确认的称呼。";
                case IDENTITY -> "没有查到符合条件的已确认身份资料。";
                case SELF_HISTORY -> "在能核实为当前账号本人发出的 QQ 历史消息中，未查到符合条件的发言。";
            });
        }
        // 模型和审计共用同一份结果。范围说明及近期上下文不计入实际命中数。
        bindings = recalled.bindings();
        if (memory.v2() != null) {
            memory.v2().prepare(inputId, caller.name(), trace.scope(), sentences, bindings);
            preparedRead = true;
        }
        registerDeliverySources(bindings);
        var result = new Result(kind, recalled.matchCount(), sentences);
        eventId = trace.write(recalled.eventId(), "memory", "memory_call_result", result);
        return result;
    }

    /** 解析模型发出的 {@code memory_call} 原生工具参数、执行查询，并将 assistant 调用与 tool 回执按 OpenAI 格式追加进消息列表。 */
    public void appendToolResult(List<Map<String, Object>> messages, String id, String arguments, String assistantText)
            throws IOException, InterruptedException {
        var input = parseArguments(arguments);
        var result = read(input.kind(), input.query(), Integer.MAX_VALUE, resolveAuthor(input.authorRef()));
        messages.add(Map.of("role", "assistant", "content", assistantText, "tool_calls", List.of(
                Map.of("id", id, "type", "function", "function", Map.of("name", NAME, "arguments", arguments)))));
        messages.add(Map.of("role", "tool", "tool_call_id", id, "content", String.join("\n", result.sentences())));
    }

    private record Input(QueryKind kind, String query, String authorRef) {}

    /** 严格解析原生工具参数，仅已协商的 HISTORY 可指定 author_ref。 */
    private Input parseArguments(String arguments) throws IOException {
        String rule = "memory_call 参数须含字符串 kind 与 query，只有支持的 HISTORY 可附 author_ref";
        try {
            var input = JSON.fromJson(arguments, JsonObject.class);
            if (input != null && input.get("kind") instanceof JsonPrimitive kind && kind.isString()
                    && input.get("query") instanceof JsonPrimitive query && query.isString()) {
                String ref = null;
                if (input.has("author_ref")) {
                    if (!authorHistory() || !(input.get("author_ref") instanceof JsonPrimitive author) || !author.isString())
                        throw new IOException(rule);
                    ref = author.getAsString();
                }
                if (input.size() == (ref == null ? 2 : 3) && (ref == null || validQuery(kind.getAsString(), query.getAsString(), ref)))
                    return new Input(QueryKind.valueOf(kind.getAsString()), query.getAsString(), ref);
            }
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException(rule, error);
        }
        throw new IOException(rule);
    }
}
