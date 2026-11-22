package neuralcloud.controlplane.memory;

import com.google.gson.*;

import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class ResidentV2 extends ResidentMemory {
    private static final String SOURCE = "neuralcloud-java";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final MemoryJournal journal;
    private final String grantToken;
    private String stream;
    private boolean audienceReads;

    public ResidentV2(
            HttpClient client,
            URI endpoint,
            String character,
            String token,
            boolean scoped,
            MemoryJournal journal,
            String grantToken) {
        super(client, endpoint, character, token, scoped);
        this.journal = journal;
        this.grantToken = grantToken;
    }

    public String stream() {
        return stream;
    }

    public boolean authorGrantsEnabled() {
        return grantToken != null;
    }

    static JsonObject object(Object... pairs) {
        var result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    static String string(JsonObject o, String k) throws IOException {
        if (o.get(k) instanceof JsonPrimitive v && v.isString() && !v.getAsString().isBlank())
            return v.getAsString();
        throw new IOException("Memory v2 缺少字符串 " + k);
    }

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
        return ctx;
    }

    @Override
    public void verify(boolean qqEnabled) throws IOException, InterruptedException {
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
        if (grantToken != null) {
            if (!qqEnabled) throw new IOException("原作者授权需要已配置的 OpenQQ 渠道");
            required.addAll(List.of("audience_reads", "author_grants"));
        }
        if (qqEnabled && scopedRecall()) required.addAll(List.of("self_identity", "self_history"));
        caps.forEach(x -> required.remove(x.getAsString()));
        if (!required.isEmpty()) throw new IOException("Memory v2 缺少必需能力 " + required);
        stream = id;
        recover();
    }

    // 只有渠道适配器传入已认证的私聊；命令正文没有代填发言人或授予者的字段。
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
                                "/memory grant ([0-9a-f]{64})@([1-9][0-9]{0,17}) (\\S+) (READ|CITE) (.+)",
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

    public synchronized void recover() throws IOException, InterruptedException {
        for (var op : journal.pending(stream)) transmit(op, true);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

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
                raw = request("v2/ingest", body);
            } catch (ServiceError e) {
                if (Set.of(403, 409, 413, 422).contains(e.status)) {
                    journal.ack(event, object("status", "REFUSED", "code", e.code));
                    if (reconcile) return null;
                }
                throw e;
            }
        }
        if (!"DURABLE".equals(string(raw, "status"))) throw new IOException("记忆写入缺少持久回执");
        var rc =
                new Receipt(
                        string(raw, "record_id"),
                        integer(raw, "revision", 1),
                        integer(raw, "seq", 1));
        if (!rc.recordId().matches("[0-9a-f]{64}"))
            throw new IOException("Memory v2 record_id 不合法");
        journal.ack(event, raw);
        return rc;
    }

    public synchronized Receipt ingestEvent(
            Trace.Scope scope,
            String event,
            Instant at,
            Kind kind,
            String content,
            String messageId,
            JsonObject origin)
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
                        : scope.qq().isPresent() ? "当前 QQ 私聊" : "当前控制台会话");
        // Optional operator/channel attestation accompanies the inbound observation, never model
        // output.
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
        return submit(body);
    }

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

    private Receipt submit(JsonObject body) throws IOException, InterruptedException {
        String event = string(body, "event_id");
        journal.enqueue(event, body, stream);
        for (var pending : journal.pending(stream))
            if (!event.equals(string(pending, "event_id"))) transmit(pending, true);
        return transmit(body, false);
    }

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

    record Snapshot(String stream, long seq, List<JsonObject> items, String assessment) {}

    private Snapshot snapshot(JsonObject raw, Trace.Scope scope, boolean recall)
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
                string(item, "text");
            }
            rows.add(item);
        }
        String assessment = recall ? string(raw.getAsJsonObject("assessment"), "state") : "";
        return new Snapshot(id, seq, rows, assessment);
    }

    private static void checkRef(JsonObject ref) throws IOException {
        if (!string(ref, "record_id").matches("[0-9a-f]{64}")) throw new IOException("来源标识无效");
        integer(ref, "revision", 1);
    }

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
                        "QUICK",
                        "limit",
                        kind == QueryKind.SELF_IDENTITY ? 1 : 4,
                        "before",
                        before.toString());
        return audienceRequest("v2/recall", body, scope, true);
    }

    private Snapshot audienceRequest(
            String path, JsonObject body, Trace.Scope scope, boolean recall)
            throws IOException, InterruptedException {
        if (audienceReads && "HISTORY".equals(string(body, "kind")))
            body.addProperty("use", "CITE");
        return snapshot(request(path, body), scope, recall);
    }

    private Snapshot readItems(Trace.Scope scope, String role, String kind, List<String> ids)
            throws IOException, InterruptedException {
        return audienceRequest(
                "v2/read",
                object(
                        "context",
                        context(scope, role),
                        "kind",
                        kind,
                        "targets",
                        ids.stream().map(id -> Map.of("record_id", id)).toList(),
                        "length",
                        2048),
                scope,
                false);
    }

    static String render(JsonObject item, String purpose) throws IOException {
        if (!string(item, "status").equals("ONLINE")) return "此前引用的原依据已失效。";
        String value = string(item, "text");
        if (purpose.equals("SELF_IDENTITY")) return "当前账号经确认的称呼是「" + value + "」。";
        String venue = item.has("source_venue") ? "在" + string(item, "source_venue") : "";
        String who = item.has("speaker") ? string(item, "speaker") : "来源记录";
        String kind = string(item, "kind");
        String label =
                switch (kind) {
                    case "impression", "summary" -> "派生线索";
                    case "tool_attempt" -> "工具尝试，结果待核实";
                    case "delivery_receipt" -> "渠道送达回执";
                    case "tool_result" -> "工具执行回执";
                    default -> "原话";
                };
        if (item.has("assertion_active"))
            label += item.get("assertion_active").getAsBoolean() ? "，当前安排" : "，历史安排";
        String when =
                string(item, "occurred_at")
                        + (item.has("action_id") ? "，动作 " + string(item, "action_id") : "");
        return who + venue + "的" + label + "（" + when + "）：“" + value + "”。可在当前会话引用。";
    }

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

    void prepare(
            String input,
            String role,
            Trace.Scope scope,
            List<String> material,
            List<MemoryJournal.Read> bindings)
            throws IOException {
        if (!bindings.isEmpty())
            journal.prepare(input, role, scope.conversationId(), JSON.toJson(material), bindings);
    }

    void delivered(String input) throws IOException {
        journal.delivered(input);
    }

    record Refresh(
            List<String> messages,
            List<MemoryJournal.Read> updates,
            List<MemoryJournal.Read> evidence) {}

    void finishRefresh(String input, Refresh refresh) throws IOException {
        journal.finishRefresh(input, refresh.updates());
    }

    private static boolean sameEvidence(JsonObject left, JsonObject right) {
        var a = left.deepCopy();
        var b = right.deepCopy();
        a.remove("view");
        b.remove("view");
        return a.equals(b);
    }

    private static boolean depends(MemoryJournal.Read read, String id) {
        if (read.id().equals(id)) return true;
        var refs = read.item().getAsJsonArray("source_refs");
        if (refs != null)
            for (var r : refs)
                if (r.getAsJsonObject().get("record_id").getAsString().equals(id)) return true;
        return false;
    }

    Refresh refresh(Trace.Scope scope, String role) throws IOException, InterruptedException {
        var registered = journal.reads(role, scope.conversationId());
        if (registered.isEmpty()) return new Refresh(List.of(), List.of(), List.of());
        var health = request("v2/health", null);
        long high = integer(health, "head_seq", 0);
        String currentStream = string(health, "stream_id");
        long cursor = high;
        var affected = new LinkedHashSet<MemoryJournal.Read>();
        for (var r : registered) {
            if (!r.stream().equals(currentStream) || r.seq() > high) affected.add(r);
            else cursor = Math.min(cursor, r.seq());
        }
        // Each item brings its original lower bound. A late registration is revisited even if
        // another input already consumed the change.
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
                            && depends(r, string(change, "record_id"))) affected.add(r);
            }
            cursor = next;
        }
        var messages = new LinkedHashSet<String>();
        var updates = new ArrayList<MemoryJournal.Read>();
        var evidence = new LinkedHashMap<List<String>, MemoryJournal.Read>();
        for (var r : registered) {
            JsonObject item = r.item();
            long seen = high;
            if (affected.contains(r)) {
                var now = readItems(scope, role, r.kind(), List.of(r.id()));
                seen = now.seq();
                var latest = now.items().getFirst();
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
            if (seen != r.seq() || !r.stream().equals(currentStream))
                updates.add(
                        new MemoryJournal.Read(
                                role,
                                scope.conversationId(),
                                r.kind(),
                                r.id(),
                                currentStream,
                                seen,
                                item,
                                r.input()));
        }
        return new Refresh(List.copyOf(messages), updates, List.copyOf(evidence.values()));
    }

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
                            latest.get("assertion_active"), d.item().get("assertion_active")))
                throw new IOException("行动依据已经变化，需要重新取证");
        }
    }

    public void verifyDelivery(Trace trace) throws IOException, InterruptedException {
        var now =
                readItems(
                        trace.scope(),
                        "ROUTER",
                        "HISTORY",
                        List.copyOf(trace.sharedSources().keySet()));
        for (var item : now.items()) {
            if (!"ONLINE".equals(string(item, "status"))) throw new IOException("渠道交付前引用权已失效");
            var ref = item.getAsJsonObject("ref");
            if (!Objects.equals(
                    trace.sharedSources().get(string(ref, "record_id")),
                    integer(ref, "revision", 1))) throw new IOException("渠道交付前引用依据已修订");
        }
    }
}
