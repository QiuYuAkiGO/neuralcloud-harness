package neuralcloud.controlplane.memory;

import com.google.gson.*;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Memory v2 协议客户端，继承自 {@link ResidentMemory} 并挂载本地 SQLite 持久日志 {@link MemoryJournal}。
 * 相对 v1 新增的核心控制面保障：
 * 1. 启动能力协商与未决写命令对账恢复（{@link #verify} / {@link #recover}）；
 * 2. 写前落盘（Outbox）与按 {@code v2/receipts} 幂等查证，确保网络抖动时不丢失回执也不产生重复写入；
 * 3. 原作者跨会话可见性授权/撤回（{@link #authorDecision}，使用独立的 {@code grantToken}）；
 * 4. 带快照流标识（{@code stream_id}）与全局序号（{@code read_seq}）的分域召回、热目录卡片读取与时态断言渲染；
 * 5. 基于 {@code v2/changes} 变更流的按条目增量刷新（{@link #refresh}）以及执行/渠道交付前的证据有效性复核（{@link #verifyEvidence} / {@link #verifyDelivery}）。
 */
public final class ResidentV2 extends ResidentMemory {
    private static final String SOURCE = "neuralcloud-java";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 本地持久日志，保存待发送写命令队列、已交付给模型的不可变证据与变更监视游标。 */
    private final MemoryJournal journal;
    /** 原作者可见性授权/撤回专用 Bearer 令牌，与常规读写令牌隔离；为 null 时禁用渠道授权。 */
    private final String grantToken;
    /** 受众热目录卡片的 token 预算上限（0 表示不注入热目录）。 */
    final int hotIndexTokens;
    /** 历史召回检索档位（{@code QUICK} 或 {@code THINKING}）。 */
    private final String historyProfile;
    /** 服务端在 {@code v2/health} 返回的当前记忆流标识，用于检测远端记忆库是否重建或切换。 */
    private String stream;
    /** 是否在 {@code scoped} 模式下协商通过了 {@code audience_reads} 能力（决定是否携带 {@code use=CITE}）。 */
    private boolean audienceReads;
    /** 启动时协商目录协议；每次读取的受众权限由 Memory 按当前开关裁定。 */
    private boolean identityLookup;

    boolean identityLookup() {
        return identityLookup;
    }

    /** 同一策略版本固定流和启用水位；配置发布前读到的 head 不包含随后受理的群原文。 */
    public ObserveQueue observationQueue() {
        return new ObserveQueue(
                journal,
                stream,
                () -> {
                    try {
                        var health = request("v2/health", null);
                        if (!stream.equals(string(health, "stream_id")))
                            throw new IOException("观察群 Memory 流已变更");
                        return integer(health, "head_seq", 0);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("读取观察群启用水位被中断", e);
                    }
                });
    }

    /** 兼容构造器：默认关闭热目录（预算为 0）并使用 {@code QUICK} 检索档位。 */
    public ResidentV2(
            HttpClient client,
            URI endpoint,
            String character,
            String token,
            boolean scoped,
            MemoryJournal journal,
            String grantToken) {
        this(client, endpoint, character, token, scoped, journal, grantToken, 0, "QUICK");
    }

    /** 完整构造器：绑定 HTTP 客户端、角色配置、本地 SQLite 日志、可选授权令牌以及热目录与检索档位。 */
    public ResidentV2(
            HttpClient client,
            URI endpoint,
            String character,
            String token,
            boolean scoped,
            MemoryJournal journal,
            String grantToken,
            int hotIndexTokens,
            String historyProfile) {
        super(client, endpoint, character, token, scoped);
        this.hotIndexTokens = hotIndexTokens;
        this.historyProfile = historyProfile;
        this.journal = journal;
        this.grantToken = grantToken;
    }

    /** 自动引用只有在当前服务已协商受众读契约时启用。 */
    boolean supportsAudienceReads() { return audienceReads; }

    /** 返回启动核验阶段锁定的远端记忆流标识 {@code stream_id}。 */
    public String stream() {
        return stream;
    }

    /** 返回是否配置了独立授权令牌以启用原作者跨会话授权能力。 */
    public boolean authorGrantsEnabled() {
        return grantToken != null;
    }

    /** 便捷构建 JSON 对象的辅助方法（参数按 key, value 交替传入）。 */
    static JsonObject object(Object... pairs) {
        var result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    /** 严格读取 JSON 对象中的非空字符串字段，缺失、非字符串或全空白即抛出契约异常。 */
    static String string(JsonObject o, String k) throws IOException {
        if (o.get(k) instanceof JsonPrimitive v && v.isString() && !v.getAsString().isBlank())
            return v.getAsString();
        throw new IOException("Memory v2 缺少字符串 " + k);
    }

    /** 严格读取精确整数（拒绝小数与低于 {@code minimum} 的序号），用于校验版本、流序号和主版本号。 */
    static long integer(JsonObject o, String k, long minimum) throws IOException {
        if (o.get(k) instanceof JsonPrimitive v && v.isNumber()) {
            try {
                long n = v.getAsBigDecimal().longValueExact();
                if (n >= minimum) return n;
            } catch (ArithmeticException ignored) {
            }
        }
        throw new IOException("Memory v2 序号无效 " + k);
    }

    /** 组装所有 Memory v2 请求共用的调用上下文（角色、会话、对话域、执行角色及可选 QQ 私聊双方账号）。 */
    private JsonObject context(Trace.Scope scope, String role) {
        var ctx =
                object(
                        "character_id",
                        character(),
                        "session_id",
                        scope.sessionId(),
                        "conversation_id",
                        scope.conversationId(),
                        "actor_id",
                        role,
                        "role",
                        role);
        scope.qq()
                .ifPresent(
                        q ->
                                ctx.add(
                                        "qq",
                                        object(
                                                "account_id",
                                                Long.toString(q.accountId()),
                                                "peer_id",
                                                Long.toString(q.peerId()))));
        scope.mobile().ifPresent(m -> ctx.add("mobile_member", object(
                "instance", m.instance(), "member_id", Long.toString(m.memberId()))));
        scope.qq()
                .filter(q -> !q.memberGroups().isEmpty())
                .ifPresent(
                        q -> {
                            var ids = new JsonArray();
                            q.memberGroups().forEach(id -> ids.add(id.toString()));
                            ctx.getAsJsonObject("qq").add("member_group_ids", ids);
                        });
        return ctx;
    }

    /**
     * 启动健康与能力核验：
     * 1. 核对 {@code api_major=2}、{@code character_id} 与 {@code status=TEXT_READY}，并记录 {@code stream_id}；
     * 2. 根据当前启用的功能集合（分域召回、渠道授权、QQ 私聊、热目录卡片）计算服务端必须声明的能力清单，缺一即拒启动；
     * 3. 核验通过后立即调用 {@link #recover()} 回放本地 SQLite 队列中上次进程遗留的未决写命令。
     */
    @Override
    public void verify(boolean qqEnabled) throws IOException, InterruptedException {
        verify(qqEnabled, false);
    }

    /** 仅启用动态群策略的渠道要求服务端支持已核验成员的通知读取与修订。 */
    public void verify(boolean qqEnabled, boolean managedGroups)
            throws IOException, InterruptedException {
        var health = request("v2/health", null);
        if (integer(health, "api_major", 0) != 2
                || !character().equals(string(health, "character_id"))
                || !"TEXT_READY".equals(string(health, "status")))
            throw new IOException("Memory v2 角色、版本或就绪状态不匹配");
        String id = string(health, "stream_id");
        integer(health, "head_seq", 0);
        if (!(health.get("capabilities") instanceof JsonArray caps))
            throw new IOException("Memory v2 缺少能力列表");
        audienceReads = scopedRecall() && caps.contains(new JsonPrimitive("audience_reads"));
        identityLookup = caps.contains(new JsonPrimitive("identity_lookup"));
        var required =
                new HashSet<>(
                        List.of(
                                "durable_ingest",
                                "read_refs",
                                "changes",
                                "corrections",
                                "candidates",
                                "assertions"));
        if (scopedRecall()) required.add("scoped_recall");
        if (managedGroups) required.addAll(List.of("qq_member_notices", "audience_reads"));
        if (grantToken != null) {
            if (!qqEnabled) throw new IOException("原作者授权需要已配置的 OpenQQ 渠道");
            required.addAll(List.of("audience_reads", "author_grants"));
        }
        if (qqEnabled && scopedRecall()) required.addAll(List.of("self_identity", "self_history"));
        if (hotIndexTokens > 0) required.add("audience_cards");
        caps.forEach(x -> required.remove(x.getAsString()));
        if (!required.isEmpty()) throw new IOException("Memory v2 缺少必需能力 " + required);
        stream = id;
        recover();
    }

    /**
     * 处理由渠道适配器转发的原作者跨会话可见性授权（{@code /memory grant ...}）或撤回（{@code /memory revoke ...}）命令。
     * 安全约束：仅接受已认证 QQ 私聊来源，命令正文中不存在代填发言人或授予者的字段；
     * 使用独立的 {@code grantToken} 提交，并以渠道消息 ID 构成幂等 {@code event_id}，遇 5xx/网络故障可安全重试一次取回回执。
     */
    public JsonObject authorDecision(Trace.Scope scope, long messageId, String text)
            throws IOException, InterruptedException {
        if (grantToken == null || scope.qq().isEmpty()) throw new IOException("此渠道未启用原作者授权");
        var qq = scope.qq().orElseThrow();
        var cmd =
                object(
                        "context",
                        context(scope, "ORCHESTRATOR"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        "onebot-grant:" + qq.accountId() + ":" + qq.peerId() + ":" + messageId);
        var grant =
                java.util.regex.Pattern.compile(
                                "/memory grant ([0-9a-f]{64})@([1-9][0-9]{0,17}) (\\S+) (READ|CITE)"
                                    + " (.+)",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(text);
        var revoke =
                java.util.regex.Pattern.compile(
                                "/memory revoke ([0-9a-f]{64}) (.+)",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(text);
        String path = "v2/visibility-grants";
        if (grant.matches()) {
            cmd.add(
                    "source_ref",
                    object(
                            "record_id",
                            grant.group(1),
                            "revision",
                            Long.parseLong(grant.group(2))));
            cmd.addProperty("target_conversation_id", grant.group(3));
            cmd.addProperty("use", grant.group(4));
            cmd.addProperty("reason", grant.group(5));
        } else if (revoke.matches()) {
            path += "/" + revoke.group(1) + "/revoke";
            cmd.addProperty("reason", revoke.group(2));
        } else
            throw new IOException(
                    "格式为 /memory grant 记录ID@版本 目标会话 READ或CITE 理由，或 /memory revoke 授权ID 理由");
        try {
            return request(path, cmd, grantToken);
        } catch (IOException first) {
            // 4xx 为明确的业务或契约拒绝，不重试直接返回给渠道通知原作者。
            if (first instanceof ServiceError error && error.status < 500) throw first;
            // 网络失败可能发生在提交之后。按同一渠道消息和内容取回回执，外层只发送一次。
            try {
                return request(path, cmd, grantToken);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** 启动或补偿恢复：按入队顺序将本地日志中尚未确认回执的写命令逐条对账重放。 */
    public synchronized void recover() throws IOException, InterruptedException {
        for (var op : journal.pending(stream)) transmit(op, true);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 向 Memory v2 发送单条写命令并记录持久回执：
     * - 当 {@code reconcile=true}（补偿/恢复模式）时，先查 {@code v2/receipts} 确认服务端是否早已落盘；
     * - 未落盘时按事件类型发往 {@code v2/candidates}（派生印象）或 {@code v2/ingest}（观察/回复/工具记录）；
     * - 若服务端返回 403/409/413/422 终态拒绝，则在本地记为 {@code REFUSED} 终结该命令，避免恢复队列死循环卡死。
     */
    private Receipt transmit(JsonObject body, boolean reconcile)
            throws IOException, InterruptedException {
        String event = string(body, "event_id");
        JsonObject raw =
                reconcile
                        ? request(
                                "v2/receipts?source_instance="
                                        + SOURCE
                                        + "&event_id="
                                        + encode(event),
                                null)
                        : null;
        if (raw == null || "NOT_FOUND".equals(string(raw, "status"))) {
            try {
                String path =
                        body.has("operation")
                                ? "v2/corrections"
                                : "impression".equals(string(body, "kind"))
                                        ? "v2/candidates"
                                        : "v2/ingest";
                raw = request(path, body);
            } catch (ServiceError e) {
                if (Set.of(403, 409, 413, 422).contains(e.status)) {
                    journal.ack(event, object("status", "REFUSED", "code", e.code));
                    if (reconcile) return null;
                }
                throw e;
            }
        }
        var rc = durable(raw);
        journal.ack(event, raw);
        return rc;
    }

    private static Receipt durable(JsonObject raw) throws IOException {
        if (!"DURABLE".equals(string(raw, "status"))) throw new IOException("记忆写入缺少持久回执");
        var rc =
                new Receipt(
                        string(raw, "record_id"),
                        integer(raw, "revision", 1),
                        integer(raw, "seq", 1));
        if (!rc.recordId().matches("[0-9a-f]{64}"))
            throw new IOException("Memory v2 record_id 不合法");
        return rc;
    }

    /**
     * 写入用户入站观察（{@code observation}）或渠道已确认送达的角色回复（{@code self_expression}）。
     * 自动附加场景标注（控制台 / QQ 私聊 / QQ 群聊）、渠道发件人元数据，以及可选的来源见证/跨会话引用清单 {@code origin}，
     * 随后通过 {@link #submit} 走本地写前队列顺序落盘。
     */
    public synchronized Receipt ingestEvent(
            Trace.Scope scope,
            String event,
            Instant at,
            Kind kind,
            String content,
            String messageId,
            JsonObject origin)
            throws IOException, InterruptedException {
        return ingestEvent(scope, event, at, kind, content, messageId, origin, null);
    }

    /** 受理版本仅进入本地写前日志，Memory 命令仍严格遵守 v2 的字段契约。 */
    public synchronized Receipt ingestEvent(
            Trace.Scope scope,
            String event,
            Instant at,
            Kind kind,
            String content,
            String messageId,
            JsonObject origin,
            ObserveQueue.Origin observation)
            throws IOException, InterruptedException {
        boolean inbound = kind == Kind.OBSERVATION;
        var body =
                object(
                        "context",
                        context(scope, "ROUTER"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        event,
                        "kind",
                        inbound ? "observation" : "self_expression",
                        "occurred_at",
                        at.toString(),
                        "content",
                        content);
        body.addProperty("speaker", inbound ? "用户" : character());
        body.addProperty(
                "source_venue",
                scope.group().isPresent()
                        ? "QQ 群聊"
                        : scope.qq().isPresent() ? "当前 QQ 私聊"
                        : scope.mobile().isPresent() ? "MistyAI 私聊" : "当前控制台会话");
        // 操作者/渠道见证信息或已复核的 source_refs 随事件透传给 v2 溯源，模型自由正文无权伪造此字段。
        if (origin != null) for (var e : origin.entrySet()) body.add(e.getKey(), e.getValue());
        scope.qq()
                .ifPresent(
                        q ->
                                body.add(
                                        "channel",
                                        object(
                                                "platform",
                                                "qq",
                                                "type",
                                                "qq_private",
                                                "account_id",
                                                Long.toString(q.accountId()),
                                                "sender_id",
                                                Long.toString(inbound ? q.peerId() : q.accountId()),
                                                "message_id",
                                                messageId,
                                                "conversation_id",
                                                scope.conversationId())));
        scope.group()
                .ifPresent(
                        g ->
                                body.add(
                                        "channel",
                                        object(
                                                "platform",
                                                "qq",
                                                "type",
                                                "qq_group",
                                                "account_id",
                                                Long.toString(g.accountId()),
                                                "sender_id",
                                                Long.toString(
                                                        inbound ? g.senderId() : g.accountId()),
                                                "message_id",
                                                messageId,
                                                "conversation_id",
                                                scope.conversationId())));
        if (inbound && scope.group().map(Trace.QqGroup::managed).orElse(false))
            return submitGroupText(scope, body, content, messageId, observation);
        return submit(body);
    }

    /** 显式群通知沿用首条平台消息定位。变更须带原作者和预期版本，写前日志与普通入账共用。 */
    private Receipt submitGroupText(
            Trace.Scope scope, JsonObject body, String text, String messageId, ObserveQueue.Origin observation)
            throws IOException, InterruptedException {
        if (!(text.equals("/通知")
                || text.startsWith("/通知 ")
                || text.equals("/变更通知")
                || text.startsWith("/变更通知 "))) return submit(body, observation);
        var command =
                java.util.regex.Pattern.compile("^/(通知|变更通知)(?: ([\\s\\S]+))?$").matcher(text);
        if (!command.matches() || command.group(2) == null || command.group(2).isBlank())
            throw new IOException("通知缺少正文");
        var group = scope.group().orElseThrow();
        String prefix = "onebot-notice:" + group.accountId() + ":" + group.groupId() + ":";
        var channel = body.getAsJsonObject("channel");
        channel.addProperty("type", "qq_group_notice");
        if (command.group(1).equals("通知")) {
            body.addProperty("content", command.group(2));
            body.addProperty("event_id", prefix + messageId);
            return submit(body, observation);
        }
        var edit =
                java.util.regex.Pattern.compile("([1-9][0-9]{0,14})@([1-9][0-9]{0,17}) ([\\s\\S]+)")
                        .matcher(command.group(2));
        if (!edit.matches() || edit.group(3).isBlank())
            throw new IOException("须使用 /变更通知 首条消息ID@预期版本 正文");
        var first =
                request(
                        "v2/receipts?source_instance="
                                + SOURCE
                                + "&event_id="
                                + encode(prefix + edit.group(1)),
                        null);
        if (!"DURABLE".equals(string(first, "status"))) throw new IOException("未找到该群首条通知的持久回执");
        return submit(
                object(
                        "context",
                        context(scope, "ROUTER"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        prefix + messageId,
                        "record_id",
                        string(first, "record_id"),
                        "expected_revision",
                        Long.parseLong(edit.group(2)),
                        "operation",
                        "REPLACE",
                        "reason",
                        "原作者在 QQ 群内发送变更通知",
                        "content",
                        edit.group(3),
                        "channel",
                        channel), observation);
    }

    /** 写入宿主执行器登记的工具尝试（{@code tool_attempt}）或工具回执（{@code tool_result}），以 {@code action} 串联配对。 */
    public synchronized Receipt ingestTool(
            Trace.Scope scope,
            String event,
            Instant at,
            boolean completed,
            String action,
            String content)
            throws IOException, InterruptedException {
        return submit(
                object(
                        "context",
                        context(scope, "ROUTER"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        event,
                        "kind",
                        completed ? "tool_result" : "tool_attempt",
                        "occurred_at",
                        at.toString(),
                        "content",
                        content,
                        "speaker",
                        "任务执行器",
                        "source_venue",
                        "本机会话工具",
                        "action_id",
                        action));
    }

    /**
     * 写前队列提交（Outbox 模式）：先将当前事件写入本地 SQLite {@code commands} 表，
     * 随后先把此前尚未确认的旧命令按顺序对账冲刷完毕，最后发送当前命令，严格维持单流因果顺序。
     */
    private Receipt submit(JsonObject body) throws IOException, InterruptedException {
        return submit(body, null);
    }

    private Receipt submit(JsonObject body, ObserveQueue.Origin observation)
            throws IOException, InterruptedException {
        String event = string(body, "event_id");
        journal.enqueue(event, body, stream, observation);
        for (var pending : journal.pending(stream))
            if (!event.equals(string(pending, "event_id"))) transmit(pending, true);
        return transmit(body, false);
    }

    /** 覆写基类 v1 入口，统一委派到 {@link #ingestEvent} 走 v2 持久队列协议。 */
    @Override
    public Receipt ingest(
            Trace.Scope scope,
            String event,
            Instant at,
            Kind kind,
            String content,
            String messageId)
            throws IOException, InterruptedException {
        return ingestEvent(scope, event, at, kind, content, messageId, null);
    }

    /** 一次 v2 读/召回响应的强校验快照：含流 ID、全局读水位、条目列表与服务端评估结论（如 OK/CONFLICT/INCOMPLETE）。 */
    record Snapshot(String stream, long seq, List<JsonObject> items, String assessment) {}

    /**
     * 解析并校验 {@code v2/recall} 或 {@code v2/read} 的响应快照。
     * 对每条 {@code ONLINE} 项强制核对 {@code citation_scope} 精确等于当前会话 ID，且自身 {@code ref}
     * 与全部溯源 {@code source_refs} 均具备合法的 64 位十六进制记录 ID 与正整数版本号。
     */
    private Snapshot snapshot(JsonObject raw, Trace.Scope scope, String purpose, boolean recall)
            throws IOException {
        String id = string(raw, "stream_id");
        long seq = integer(raw, "read_seq", 0);
        if (!(raw.get("items") instanceof JsonArray list))
            throw new IOException("Memory v2 items 不合法");
        var rows = new ArrayList<JsonObject>();
        for (var row : list) {
            if (!(row instanceof JsonObject item)) throw new IOException("Memory v2 返回项无效");
            if ("ONLINE".equals(string(item, "status"))) {
                if (!scope.conversationId().equals(string(item, "citation_scope"))
                        || !(item.get("source_refs") instanceof JsonArray refs)
                        || refs.isEmpty()
                        || !(item.get("ref") instanceof JsonObject ref))
                    throw new IOException("Memory v2 交付范围或版本无效");
                checkRef(ref);
                for (var source : refs) {
                    if (!(source instanceof JsonObject s)) throw new IOException("来源引用无效");
                    checkRef(s);
                }
                excerptText(item);
                // 目录返回的称呼只属于明确给出的账号，即使在原会话使用也受共享开关约束。
                if (purpose.equals("IDENTITY") && (!string(item, "identity_account_id").matches("[1-9][0-9]{4,15}")
                        || !new JsonPrimitive(true).equals(item.get("grant_dependent"))
                        || !"observation".equals(string(item, "kind"))))
                    throw new IOException("Memory v2 身份目录缺少归属账号或可复核的确认来源");
            }
            rows.add(item);
        }
        String assessment = recall ? string(raw.getAsJsonObject("assessment"), "state") : "";
        return new Snapshot(id, seq, rows, assessment);
    }

    /** 读取窗口可以恰好落在正文末尾，也可能只含空白；其类型仍必须是字符串。 */
    private static String excerptText(JsonObject item) throws IOException {
        if (item.get("text") instanceof JsonPrimitive value && value.isString()) return value.getAsString();
        throw new IOException("Memory v2 的 text 窗口须为字符串");
    }

    /** 校验单个引用对象包含合法的 64 位十六进制 {@code record_id} 及 >= 1 的 {@code revision}。 */
    private static void checkRef(JsonObject ref) throws IOException {
        if (!string(ref, "record_id").matches("[0-9a-f]{64}")) throw new IOException("来源标识无效");
        integer(ref, "revision", 1);
    }

    /**
     * 按查询类别执行 Memory v2 查证：
     * - 非分域（recent）下的 {@code HISTORY} 直接从本地 SQLite 读取最近已落盘的对话 ID 并批量 {@code v2/read}；
     * - 其余查询统一发往 {@code v2/recall}，其中历史查询使用配置的 {@code historyProfile}，身份与本人发言固定走 {@code QUICK}。
     */
    Snapshot lookup(Trace.Scope scope, String role, QueryKind kind, String query, Instant before)
            throws IOException, InterruptedException {
        if (!scopedRecall() && kind == QueryKind.HISTORY) {
            var ids = journal.recent(scope.conversationId(), stream, before.toString());
            if (ids.isEmpty()) {
                var h = request("v2/health", null);
                return new Snapshot(
                        string(h, "stream_id"), integer(h, "head_seq", 0), List.of(), "");
            }
            return readItems(scope, role, kind.name(), ids);
        }
        var body =
                object(
                        "context",
                        context(scope, role),
                        "kind",
                        kind.name(),
                        "query",
                        query,
                        "profile",
                        kind == QueryKind.HISTORY ? historyProfile : "QUICK",
                        "limit",
                        switch (kind) {
                            case SELF_IDENTITY -> 1;
                            case IDENTITY -> 50;
                            default -> 4;
                        },
                        "before",
                        before.toString());
        return audienceRequest("v2/recall", body, scope, true);
    }

    /**
     * 发送受众隔离的读/召回请求：若服务端支持 {@code audience_reads}，在历史查询中自动声明 {@code use=CITE}，
     * 使服务端只放行当前会话具备引用权限的材料（含显式 CITE 跨会话授权的记录）。
     */
    private Snapshot audienceRequest(
            String path, JsonObject body, Trace.Scope scope, boolean recall)
            throws IOException, InterruptedException {
        String purpose = string(body, "kind");
        if (audienceReads && purpose.equals("HISTORY")) body.addProperty("use", "CITE");
        return snapshot(request(path, body), scope, purpose, recall);
    }

    /** 通过 {@code v2/read} 按记录 ID 批量拉取最新在线状态与正文（单条截断长度 2048），用于回读、刷新与证据复核。 */
    Snapshot readItems(Trace.Scope scope, String role, String kind, List<String> ids)
            throws IOException, InterruptedException {
        return readWindow(scope, role, kind, ids, 0, 2048);
    }

    Snapshot readWindow(
            Trace.Scope scope, String role, String kind, List<String> ids, int start, int length)
            throws IOException, InterruptedException {
        var body =
                object(
                        "context",
                        context(scope, role),
                        "kind",
                        kind,
                        "targets",
                        ids.stream().map(id -> Map.of("record_id", id)).toList(),
                        "length",
                        length);
        if (start > 0) body.addProperty("start", start);
        return audienceRequest("v2/read", body, scope, false);
    }

    /** 普通回读只取 2048 码点。更正后的长发言按同一版本续读，防止把两次更正的正文拼在一起。 */
    String replacementText(Trace.Scope scope, JsonObject item) throws IOException, InterruptedException {
        String part = excerptText(item);
        var text = new StringBuilder(part);
        var ref = item.getAsJsonObject("ref");
        int window = 2048, offset = 0;
        while (part.codePointCount(0, part.length()) == window && text.length() <= AuditLog.DIALOGUE_CHAR_BUDGET) {
            offset += window;
            window = 16384;
            var next = readWindow(scope, "CHAT_WORKER", "HISTORY", List.of(string(ref, "record_id")), offset, window)
                    .items().getFirst();
            if (!"ONLINE".equals(string(next, "status")) || !ref.equals(next.get("ref")))
                throw new IOException("历史发言在回放期间发生更正");
            part = excerptText(next);
            text.append(part);
        }
        return text.toString();
    }

    /** 拉取当前会话的 L0 事项/人物热目录卡片指针列表（最多 12 张），并核对流标识与卡片引用合法性。 */
    JsonArray cardPointers(Trace.Scope scope) throws IOException, InterruptedException {
        var result =
                request(
                        "v2/cards",
                        object("context", context(scope, "MASTER"), "view", "L0", "limit", 12));
        if (!stream.equals(string(result, "stream_id"))
                || !(result.get("items") instanceof JsonArray items))
            throw new IOException("事项卡目录的流或 items 无效");
        for (var entry : items) {
            var card = entry.getAsJsonObject();
            checkRef(card.getAsJsonObject("ref"));
            string(card, "title");
        }
        return items;
    }

    /** 前台已接受的主观候选复用持久发件箱；服务端在事务里复核每项来源与当前受众。 */
    public synchronized Receipt rememberImpression(Trace trace, String event, Instant at, String content,
                                                   List<MemoryCall.EvidenceRef> refs)
            throws IOException, InterruptedException {
        var sources = refs.stream().map(ref -> trace.sourceRef(ref.recordId(), ref.revision())).toList();
        return submit(impressionCommand(trace.scope(), event, at, content, JSON.toJsonTree(sources)));
    }

    /** Router 生成的不可变命令由观察队列托管，通用原文恢复器无权替它首发。 */
    public JsonObject observationCandidate(
            Trace.Scope scope,
            String event,
            Instant at,
            String content,
            List<MemoryCall.EvidenceRef> refs) {
        return impressionCommand(scope, event, at, content, JSON.toJsonTree(refs));
    }

    /** 前台可引用身份目录；群观察只引用已核验的群原文，两者均按原始用途提交证据。 */
    private JsonObject impressionCommand(Trace.Scope scope, String event, Instant at, String content, JsonElement refs) {
        return object(
                "context",
                context(scope, "ROUTER"),
                "source_instance",
                SOURCE,
                "event_id",
                event,
                "kind",
                "impression",
                "author",
                "MASTER",
                "occurred_at",
                at.toString(),
                "content",
                content,
                "source_refs",
                refs);
    }

    public Optional<JsonObject> observationReceipt(String event)
            throws IOException, InterruptedException {
        var raw =
                request(
                        "v2/receipts?source_instance=" + SOURCE + "&event_id=" + encode(event),
                        null);
        if ("NOT_FOUND".equals(string(raw, "status"))) return Optional.empty();
        durable(raw);
        return Optional.of(raw);
    }

    public interface PendingReceipt {
        JsonObject await() throws IOException, InterruptedException;
    }

    public static final class SourceChanged extends IOException {
        SourceChanged(IOException reason) {
            super("印象来源版本或引用权已变化", reason);
        }
    }

    /** sendAsync 的调用点与群策略变更互斥。只有 Memory 明确拒绝来源才允许丢弃旧载荷。 */
    public PendingReceipt sendObservation(JsonObject command) {
        var request = postAsync("v2/candidates", command);
        return () -> {
            try {
                var receipt = decode(request.get());
                durable(receipt);
                return receipt;
            } catch (ServiceError e) {
                if (e.status == 409 && e.code.equals("SOURCE_CHANGED")) throw new SourceChanged(e);
                throw e;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("印象写入回执未确认", e.getCause());
            }
        };
    }

    /** 提交由 Master 修订产生的新印象候选记录（发往 {@code v2/candidates}），显式声明替换的旧印象与支撑的原始证据引用。 */
    synchronized Receipt replaceImpression(
            Trace.Scope scope,
            String event,
            JsonObject previous,
            List<JsonObject> sources,
            String content)
            throws IOException, InterruptedException {
        return submit(
                object(
                        "context",
                        context(scope, "ORCHESTRATOR"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        event,
                        "kind",
                        "impression",
                        "author",
                        "MASTER",
                        "occurred_at",
                        Instant.now().toString(),
                        "content",
                        content,
                        "source_refs",
                        sources,
                        "replaces_impression",
                        previous));
    }

    /**
     * 将单条 Memory v2 证据项渲染为给模型阅读的自然语言材料。
     * 关键业务语义：
     * - 非 {@code ONLINE} 项统一提示“原依据已失效”；
     * - 已到期证据（{@code evidence_active=false} 或断言 {@code valid_until} 已过当前时刻）只传达过期退出结论，
     *   不再复述旧安排正文，防止模型把过期内容误当成当前仍有效的约束；
     * - 活跃证据会清晰标注说话人、场景、证据级别（原话 / 派生线索 / 工具尝试 / 工具回执）、断言时态（未决或历史）及折算日期。
     */
    static String render(JsonObject item, String purpose) throws IOException {
        if (!string(item, "status").equals("ONLINE")) return "此前引用的原依据已失效。";
        String value = excerptText(item);
        if (purpose.equals("SELF_IDENTITY")) return senderNameEvidence(value);
        if (purpose.equals("IDENTITY"))
            return "QQ 账号 " + string(item, "identity_account_id") + " 经确认的称呼是「" + value + "」。";
        String venue = item.has("source_venue") ? "在" + string(item, "source_venue") : "";
        String who = item.has("speaker") ? string(item, "speaker") : "来源记录";
        // 已过期节选仍可通过 L2 显式回看；自动注入的更新只提示已到期，不再复述旧安排以防误判为当前状态。
        String expiredAt = null;
        if (item.has("evidence_active") && !item.get("evidence_active").getAsBoolean())
            expiredAt = string(item, "evidence_valid_until");
        if (item.has("assertion_active") && !item.get("assertion_active").getAsBoolean()) {
            var assertion = item.getAsJsonObject("assertion");
            if (assertion.has("valid_until")) {
                String end = string(assertion, "valid_until");
                if (!Instant.now().isBefore(Instant.parse(end))) expiredAt = end;
            }
        }
        if (expiredAt != null)
            return who + venue + "的这条证据已于 " + expiredAt + " 到期，已经退出当前安排。当前状态需另有有效来源才能确认。";
        String kind = string(item, "kind");
        String label =
                switch (kind) {
                    case "impression", "summary" -> "派生线索";
                    case "tool_attempt" -> "工具尝试，结果待核实";
                    case "delivery_receipt" -> "渠道送达回执";
                    case "tool_result" -> "工具执行回执";
                    default -> "原话";
                };
        if (item.has("assertion_active")) {
            boolean active = item.get("assertion_active").getAsBoolean();
            label +=
                    switch (string(item.getAsJsonObject("assertion"), "predicate")) {
                        case "commitment" -> active ? "，未决承诺" : "，历史承诺";
                        case "deadline" -> active ? "，有效期限" : "，历史期限";
                        default -> active ? "，当前安排" : "，历史安排";
                    };
        }
        String when =
                string(item, "occurred_at")
                        + (item.has("action_id") ? "，动作 " + string(item, "action_id") : "");
        if (item.has("resolved_dates")) when += "，按发言日折算 " + item.get("resolved_dates");
        return who + venue + "的" + label + "（" + when + "）：“" + value + "”。可在当前会话引用。";
    }

    /** 将单条响应项与本次快照水位封装为可持久化的 {@link MemoryJournal.Read} 绑定对象。 */
    static MemoryJournal.Read binding(
            Trace.Scope scope, String role, String kind, JsonObject item, Snapshot s, String input)
            throws IOException {
        return new MemoryJournal.Read(
                role,
                scope.conversationId(),
                kind,
                string(item.getAsJsonObject("ref"), "record_id"),
                s.stream(),
                s.seq(),
                item,
                input);
    }

    /** 登记常规召回/刷新材料包及其证据绑定，同时注册到 {@code watches} 表以便后续轮次监听变更。 */
    void prepare(
            String input,
            String role,
            Trace.Scope scope,
            List<String> material,
            List<MemoryJournal.Read> bindings)
            throws IOException {
        if (!bindings.isEmpty())
            journal.prepare(input, role, scope.conversationId(), JSON.toJson(material), bindings, true);
    }

    /** 登记 Master 热目录材料包及其审计证据（{@code watch=false}，因为热目录每轮重建，不建长期监视）。 */
    void prepareHot(
            String input, Trace.Scope scope, String block, List<MemoryJournal.Read> bindings)
            throws IOException {
        journal.prepare(
                input,
                "MASTER",
                scope.conversationId(),
                JSON.toJson(List.of(block)),
                bindings,
                false);
    }

    /** 将指定输入批次在本地日志中标记为已交付给模型。 */
    void delivered(String input) throws IOException {
        journal.delivered(input);
    }

    /** 单轮变更刷新结果：待注入给模型的更正提示、需推进的水位更新项、以及构成更正依据的在线证据列表。 */
    record Refresh(
            List<String> messages,
            List<MemoryJournal.Read> updates,
            List<MemoryJournal.Read> evidence) {}

    /** 在模型完成消费刷新提示后，事务性推进本地 {@code watches} 水位并合并收敛的重复监视。 */
    void finishRefresh(String input, Refresh refresh) throws IOException {
        journal.finishRefresh(input, refresh.updates());
    }

    /** 忽略仅影响视图级别的 {@code view} 字段，比较两条证据载荷在业务语义上是否等价。 */
    private static boolean sameEvidence(JsonObject left, JsonObject right) {
        var a = left.deepCopy();
        var b = right.deepCopy();
        a.remove("view");
        b.remove("view");
        return a.equals(b);
    }

    /** 判断某条已监视证据是否直接匹配变更记录 ID，或其派生的 {@code source_refs} 间接依赖于该记录 ID。 */
    private static boolean depends(MemoryJournal.Read read, String id) {
        if (read.id().equals(id)) return true;
        var refs = read.item().getAsJsonArray("source_refs");
        if (refs != null)
            for (var r : refs)
                if (r.getAsJsonObject().get("record_id").getAsString().equals(id)) return true;
        return false;
    }

    record CurrentItem(String stream, long seq, JsonObject item) {}

    /** 同一受众的历史可能登记过多次。每种查询目的按 /v2/read 的 128 条上限批量回读，保留各批的真实水位。 */
    Map<Trace.MemoryTarget, CurrentItem> reread(Trace.Scope scope, String role, Set<Trace.MemoryTarget> affected)
            throws IOException, InterruptedException {
        var byKind = new LinkedHashMap<String, List<String>>();
        for (var target : affected)
            byKind.computeIfAbsent(target.kind(), ignored -> new ArrayList<>()).add(target.recordId());
        var current = new HashMap<Trace.MemoryTarget, CurrentItem>();
        for (var group : byKind.entrySet()) {
            var ids = group.getValue();
            for (int start = 0; start < ids.size(); start += 128) {
                var batch = ids.subList(start, Math.min(start + 128, ids.size()));
                var now = readItems(scope, role, group.getKey(), batch);
                var received = new HashSet<String>();
                for (var item : now.items()) {
                    String id = item.has("ref") ? string(item.getAsJsonObject("ref"), "record_id")
                            : string(item, "record_id");
                    received.add(id);
                    current.put(new Trace.MemoryTarget(group.getKey(), id), new CurrentItem(now.stream(), now.seq(), item));
                }
                if (now.items().size() != batch.size() || !received.containsAll(batch))
                    throw new IOException("Memory v2 回读结果与请求的记录不匹配");
            }
        }
        return current;
    }

    /**
     * 每轮开始前对当前角色在本会话已监视的证据执行增量变更检测：
     * 1. 先查 {@code v2/health} 获取最新流与头序号 {@code high}，再将跨会话和时效依据加入待检集合；
     * 2. 从所有同流监视项的最小 {@code seq} 起分页拉取 {@code v2/changes}，定位自身或上游 {@code source_refs} 发生变更的监视项；
     * 3. 分批重读受影响条目：若内容/状态改变则生成“记忆更正”提示；若原先活跃的时态断言翻转为非活跃（例如安排被替代或到期），
     *    还会按同一 {@code (subject_id, predicate)} 自动召回当前替代安排一并注入模型。
     */
    Refresh refresh(Trace.Scope scope, String role) throws IOException, InterruptedException {
        var registered = journal.reads(role, scope.conversationId());
        if (registered.isEmpty()) return new Refresh(List.of(), List.of(), List.of());
        var health = request("v2/health", null);
        long high = integer(health, "head_seq", 0);
        String currentStream = string(health, "stream_id");
        long cursor = high;
        var affected = new LinkedHashSet<Trace.MemoryTarget>();
        for (var r : registered) {
            // 切换会话隔离和跨过有效期都不会写入变更流。这些依据必须在本轮首个模型输入前按接收会话重读。
            if (r.item().has("evidence_valid_until") || r.item().has("assertion")
                    || r.item().has("grant_dependent") && r.item().get("grant_dependent").getAsBoolean())
                affected.add(new Trace.MemoryTarget(r.kind(), r.id()));
            if (!r.stream().equals(currentStream) || r.seq() > high) affected.add(new Trace.MemoryTarget(r.kind(), r.id()));
            else cursor = Math.min(cursor, r.seq());
        }
        // 每条监视项各自携带原始读水位下界；晚登记的条目即便变更已被其他输入消费过，也会被重新检查到。
        while (cursor < high) {
            var page =
                    request(
                            "v2/changes?stream_id="
                                    + encode(currentStream)
                                    + "&after="
                                    + cursor
                                    + "&through="
                                    + high,
                            null);
            long next = integer(page, "next_after", 0);
            if (next <= cursor || next > high || !currentStream.equals(string(page, "stream_id")))
                throw new IOException("变更游标无效");
            for (var e : page.getAsJsonArray("items")) {
                var change = e.getAsJsonObject();
                for (var r : registered)
                    if (integer(change, "seq", 1) > r.seq()
                            && depends(r, string(change, "record_id"))) affected.add(new Trace.MemoryTarget(r.kind(), r.id()));
            }
            cursor = next;
        }
        var messages = new LinkedHashSet<String>();
        var updates = new ArrayList<MemoryJournal.Read>();
        var evidence = new LinkedHashMap<List<String>, MemoryJournal.Read>();
        var current = reread(scope, role, affected);
        for (var r : registered) {
            JsonObject item = r.item();
            String seenStream = currentStream;
            long seen = high;
            var now = current.get(new Trace.MemoryTarget(r.kind(), r.id()));
            if (now != null) {
                seenStream = now.stream();
                seen = now.seq();
                var latest = now.item();
                if (!sameEvidence(latest, item)) {
                    messages.add("记忆更正：" + render(latest, r.kind()));
                    evidence.put(
                            List.of(r.kind(), r.id()),
                            new MemoryJournal.Read(
                                    role,
                                    scope.conversationId(),
                                    r.kind(),
                                    r.id(),
                                    now.stream(),
                                    seen,
                                    latest,
                                    r.input()));
                }
                // 当一条原本活跃的断言（如日程承诺/期限）变为非活跃时，主动补查同一主题与谓词下的新替代记录。
                if (item.has("assertion_active")
                        && item.get("assertion_active").getAsBoolean()
                        && latest.has("assertion_active")
                        && !latest.get("assertion_active").getAsBoolean()) {
                    var assertion = latest.getAsJsonObject("assertion");
                    var request =
                            object(
                                    "context",
                                    context(scope, role),
                                    "kind",
                                    r.kind(),
                                    "query",
                                    string(assertion, "subject_id"),
                                    "subject_id",
                                    string(assertion, "subject_id"),
                                    "predicate",
                                    string(assertion, "predicate"),
                                    "profile",
                                    "QUICK",
                                    "limit",
                                    4);
                    var replacement = audienceRequest("v2/recall", request, scope, true);
                    for (var row : replacement.items()) {
                        messages.add("记忆更正：" + render(row, r.kind()));
                        var source = binding(scope, role, r.kind(), row, replacement, r.input());
                        evidence.put(List.of(source.kind(), source.id()), source);
                    }
                }
                item = latest;
            }
            if (seen != r.seq() || !r.stream().equals(seenStream)
                    || !sameEvidence(r.item(), item))
                updates.add(
                        new MemoryJournal.Read(
                                role,
                                scope.conversationId(),
                                r.kind(),
                                r.id(),
                                seenStream,
                                seen,
                                item,
                                r.input()));
        }
        return new Refresh(List.copyOf(messages), updates, List.copyOf(evidence.values()));
    }

    /**
     * 行动/写入前的证据一致性复核：逐条重读依赖项，校验记忆流、在线状态、自身与上游版本号、
     * 以及时效与断言活性均未发生漂移；任一项不符立即抛错阻断，迫使调用方重新取证。
     */
    void verifyEvidence(Trace.Scope scope, String role, List<MemoryJournal.Read> deps)
            throws IOException, InterruptedException {
        for (var d : deps) {
            var now = readItems(scope, role, d.kind(), List.of(d.id()));
            var latest = now.items().getFirst();
            if (!now.stream().equals(d.stream())
                    || !"ONLINE".equals(string(latest, "status"))
                    || !latest.get("ref").equals(d.item().get("ref"))
                    || !latest.get("source_refs").equals(d.item().get("source_refs"))
                    || !Objects.equals(
                            latest.get("evidence_active"), d.item().get("evidence_active"))
                    || !Objects.equals(
                            latest.get("assertion_active"), d.item().get("assertion_active")))
                throw new IOException("行动依据已经变化，需要重新取证");
        }
    }

    /**
     * 渠道向外发送回复前的最终交付门禁复核：
     * 以原查询用途复核每条来源的引用权及修订号，同时检查回执的来源数量和本轮最早有效截止时间。
     */
    public void verifyDelivery(Trace trace) throws IOException, InterruptedException {
        var sources = trace.sharedSources();
        // 送达后的自述须能按 Memory 写入契约持久化，不能先外发再发现来源超过上限。
        if (sources.keySet().stream().map(Trace.MemoryTarget::recordId).distinct().count() > 128)
            throw new IOException("自述的 source_refs 超过 Memory 允许的 128 项");
        var current = reread(trace.scope(), "ROUTER", sources.keySet());
        for (var entry : current.entrySet()) {
            var item = entry.getValue().item();
            if (!"ONLINE".equals(string(item, "status"))) throw new IOException("渠道交付前引用权已失效");
            var ref = item.getAsJsonObject("ref");
            if (!Objects.equals(
                    sources.get(entry.getKey()), integer(ref, "revision", 1)))
                throw new IOException("渠道交付前引用依据已修订");
        }
        // 受众复核本身也可能经过到期时刻，须用复核完成时的时间判定。
        if (!Instant.now().isBefore(trace.validUntil())) throw new IOException("渠道交付前引用的证据已到期");
    }
}
