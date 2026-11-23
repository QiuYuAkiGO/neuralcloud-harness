package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.ConsoleServer.Trace.Scope;
import neuralcloud.controlplane.console.ConsoleServer.Trace.QqPrivate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 常驻记忆服务 HTTP 客户端基类，实现 Memory v1 协议并为 {@link ResidentV2} 提供基础传输与鉴权能力。
 * 核心职责：
 * 1. 启动健康与能力核验（{@code v1/health}），确认角色一致、状态为 {@code TEXT_READY}，以及按需检查分域/本人发言能力；
 * 2. 事件持久入库（{@code v1/ingest}）并强校验 {@code DURABLE} 回执与 64 位十六进制记录 ID；
 * 3. 按 ID 回读（{@code v1/records/{id}}）、分域历史召回（{@code v1/recall}）、本人跨会话发言（{@code v1/recall/qq-self-history}）
 *    以及经操作者确认的身份称呼查询；所有召回结果均在客户端二次核验渠道、来源、隐私级别与时间边界后才格式化交付给模型。
 */
public class ResidentMemory {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    /** 本控制面写入记忆服务时声明的固定来源实例标识。 */
    private static final String SOURCE = "neuralcloud-java";
    /** 渲染给模型的发言时间统一采用北京时间，与中文对话习惯保持一致。 */
    private static final DateTimeFormatter SPOKEN_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    /** 回读单条记录时视作“不可用/已下线”的非致命状态集合，命中时返回 {@link Optional#empty()}。 */
    private static final Set<String> UNAVAILABLE = Set.of(
            "NOT_FOUND", "ARCHIVED_OFFLINE", "RETRACTED", "SOURCE_CHANGED", "INACTIVE");

    /** 记忆写入的基本事件类型：用户入站观察与角色已送达发言。 */
    public enum Kind {
        OBSERVATION("observation"), SELF_EXPRESSION("self_expression");

        private final String value;

        Kind(String value) {
            this.value = value;
        }
    }

    /** 记忆服务确认落盘（{@code DURABLE}）后返回的持久回执：记录哈希 ID、修订版本号与全局流序号。 */
    public record Receipt(String recordId, long revision, long sequence) {}
    /** 召回或回读并通过边界核验后的单条记忆条目（含发生时间戳及可直接注入模型的自然语言描述）。 */
    public record Match(String recordId, double occurredAt, String message) {}

    private final HttpClient client;
    private final URI endpoint;
    private final String character;
    private final String token;
    private final boolean scopedRecall;

    public ResidentMemory(HttpClient client, URI endpoint, String character, String token, boolean scopedRecall) {
        this.client = client;
        this.endpoint = endpoint;
        this.character = character;
        this.token = token;
        this.scopedRecall = scopedRecall;
    }

    /** 判断本地审计链中的 {@code memory_durable} 回执是否归属当前角色（兼容早期未写 characterId 的 recent 日志）。 */
    boolean acceptsReceipt(JsonObject event) {
        return (!scopedRecall && !event.has("characterId")) || character.equals(text(event, "characterId"));
    }

    /** 启动核验（未启用 QQ 私聊渠道时的简化入口）。 */
    public void verify() throws IOException, InterruptedException {
        verify(false);
    }

    /**
     * 启动阶段核验记忆服务可用性与能力契约：
     * 校验 {@code character_id} 匹配、状态为 {@code TEXT_READY}；
     * 若启用 {@code scopedRecall} 则进一步要求支持工作流与来源事件 v2，启用 QQ 私聊时还须支持 {@code qq_self_history}。
     */
    public void verify(boolean qqPrivateEnabled) throws IOException, InterruptedException {
        var result = request("v1/health", null);
        if (!character.equals(text(result, "character_id")) || !"TEXT_READY".equals(text(result, "status"))) {
            throw new IOException("记忆服务未就绪或 character_id 与配置不符");
        }
        if (scopedRecall && (!(result.get("workflow") instanceof JsonObject)
                || !(result.get("source_event_versions") instanceof JsonArray versions)
                || !versions.contains(new JsonPrimitive(2)))) {
            throw new IOException("生产记忆服务须支持按会话检索和来源事件 v2");
        }
        if (scopedRecall && qqPrivateEnabled && !new JsonPrimitive(true).equals(result.get("qq_self_history"))) {
            throw new IOException("生产 QQ 私聊需要记忆服务支持 qq_self_history");
        }
    }

    /**
     * 向 Memory v1 提交一条入站观察或角色回复事件，等待服务端确认 {@code DURABLE} 后返回持久回执。
     * 在 {@code scopedRecall} 下的 QQ 私聊会升级为 {@code schema_version=2} 并附带精确的平台、发件人与渠道消息 ID 元数据。
     */
    public Receipt ingest(Scope scope, String event, Instant at, Kind kind, String content, String channelMessageId)
            throws IOException, InterruptedException {
        if (content.codePointCount(0, content.length()) > 32768) {
            throw new IOException("记忆 content 最多允许 32768 个 Unicode 字符");
        }
        var body = new HashMap<String, Object>(Map.of("schema_version", 1, "source_instance", SOURCE,
                "character_id", character, "session_id", scope.sessionId(), "conversation_id", conversationId(scope),
                "event_id", event, "occurred_at", epochSeconds(at), "kind", kind.value, "privacy", "private",
                "content", content));
        if (scopedRecall && scope.qq().isPresent()) {
            var channel = scope.qq().orElseThrow();
            body.put("schema_version", 2);
            body.put("channel", Map.of("platform", "qq", "account_id", Long.toString(channel.accountId()),
                    "sender_id", Long.toString(kind == Kind.OBSERVATION ? channel.peerId() : channel.accountId()),
                    "conversation_id", channel.conversationId(), "message_id", channelMessageId));
        }
        var reply = request("v1/ingest", body);
        String id = text(reply, "record_id");
        long revision = positive(reply, "revision");
        long seq = positive(reply, "seq");
        if (!"DURABLE".equals(text(reply, "status")) || !id.matches("[0-9a-f]{64}") || revision == 0 || seq == 0) {
            throw new IOException("记忆服务未返回有效的 DURABLE 回执");
        }
        return new Receipt(id, revision, seq);
    }

    /** 返回当前绑定的角色标识。 */
    public String character() {
        return character;
    }

    /** 返回当前是否启用了分域检索（{@code scoped}）模式。 */
    boolean scopedRecall() {
        return scopedRecall;
    }

    /**
     * 按记录 ID 回读单条历史正文（用于 v1 {@code recent} 模式）。
     * 已撤回/归档/下线状态返回空；在线记录须通过角色、来源实例、会话 ID、隐私标记与事件类别的完整一致性检查。
     */
    public Optional<String> read(Scope scope, String recordId) throws IOException, InterruptedException {
        var reply = request("v1/records/" + recordId + "?length=2048", null);
        String status = text(reply, "status");
        if (!recordId.equals(text(reply, "record_id"))) {
            throw new IOException("记忆读取的 record_id 与请求不符");
        }
        if (UNAVAILABLE.contains(status)) {
            return Optional.empty();
        }
        String kind = text(reply, "kind");
        String content = text(reply, "content");
        double time = occurredAt(reply);
        if (!"ONLINE".equals(status) || positive(reply, "revision") == 0 || content.isBlank()
                || time < 0
                || !(reply.get("source") instanceof JsonObject source)
                || !character.equals(text(source, "character_id")) || !SOURCE.equals(text(source, "source_instance"))
                || !scope.sessionId().equals(text(source, "session_id"))
                || !conversationId(scope).equals(text(source, "conversation_id"))
                || !"private".equals(text(source, "privacy")) || !kind.equals(text(source, "kind"))
                || !(Kind.OBSERVATION.value.equals(kind) || Kind.SELF_EXPRESSION.value.equals(kind))) {
            throw new IOException("记忆记录不符合当前会话的来源或内容契约");
        }
        return Optional.of(render(scope, kind, content, time));
    }

    /**
     * 在当前会话范围内按话题检索历史发言（仅 {@code scopedRecall} 模式可用）。
     * 使用 {@code Math.nextDown(epochSeconds(receivedAt))} 作为严格小于本轮到达时间的上界，
     * 对返回的候选逐条执行 {@link #allowed} 权限与归属过滤，去重后最多保留 4 条并按时间正序返回。
     */
    public List<Match> search(Scope scope, String query, Instant receivedAt) throws IOException, InterruptedException {
        // recent 模式使用按 ID 回读的接口，检索接口的线索长度上限是 256 个 Unicode 字符。
        if (!scopedRecall || query.codePointCount(0, query.length()) > 256) {
            return List.of();
        }
        double until = Math.nextDown(epochSeconds(receivedAt));
        var body = new HashMap<String, Object>(Map.of("query", query, "limit", 16, "mode", "auto",
                "workflow", "enabled", "constraints", Map.of("conversation_id", scope.conversationId(), "until", until)));
        scope.qq().ifPresent(qq -> body.put("subject", subject(qq.peerId())));
        var items = recallItems(request("v1/recall", body));
        var matches = new ArrayList<Match>();
        // 索引的候选结果还须通过渠道、原始来源、可见性和时间边界检查。
        for (var raw : items) {
            if (raw instanceof JsonObject item && item.get("source") instanceof JsonObject source) {
                String id = text(item, "record_id");
                String kind = text(item, "kind");
                String content = text(item, "content");
                double time = occurredAt(item);
                if ("ONLINE".equals(text(item, "status")) && !id.isBlank() && id.length() <= 256
                        && !content.isBlank() && positive(item, "revision") > 0
                        && time >= 0 && time <= until
                        && kind.equals(text(source, "kind")) && allowed(scope, source, kind)) {
                    matches.add(new Match(id, time, render(scope, kind, content, time)));
                }
            }
        }
        return matches.stream().distinct().limit(4).sorted(Comparator.comparingDouble(Match::occurredAt)).toList();
    }

    /**
     * 按已认证的 QQ 私聊发件人账号跨会话查询其本人以往的原话节选（{@code v1/recall/qq-self-history}）。
     * 账号完全由渠道绑定传入，禁止外部或模型指定其他用户账号；返回项严格校验来源渠道、版本与时间早于本轮。
     */
    List<Match> selfHistory(QqPrivate qq, String query, Instant receivedAt) throws IOException, InterruptedException {
        // 账号只取自入站私聊。记忆服务按作者和原始消息元数据筛选，只回传本人发言节选。
        String peer = Long.toString(qq.peerId());
        double before = epochSeconds(receivedAt);
        var result = request("v1/recall/qq-self-history",
                Map.of("account_id", peer, "query", query, "before", before, "limit", 4));
        if (!character.equals(text(result, "character_id")) || !peer.equals(text(result, "account_id"))
                || !(result.get("items") instanceof JsonArray items) || items.size() > 4) {
            throw new IOException("本人发言查询的角色、账号或结果列表不符合请求");
        }
        var matches = new ArrayList<Match>();
        for (var raw : items) {
            if (raw instanceof JsonObject item) {
                String origin = text(item, "origin");
                String id = text(item, "record_id");
                String excerpt = text(item, "excerpt");
                double time = occurredAt(item);
                if (Set.of("qq_group", "qq_private").contains(origin) && !id.isBlank() && id.length() <= 256
                        && positive(item, "revision") > 0 && time >= 0 && time < before
                        && !excerpt.isBlank() && excerpt.codePointCount(0, excerpt.length()) <= 2048) {
                    String channel = "qq_group".equals(origin) ? "QQ 群聊" : "QQ 私聊";
                    matches.add(new Match(id, time, "北京时间 " + SPOKEN_AT.format(Instant.ofEpochSecond((long) time))
                            + "，当前账号本人在" + channel + "中的发言节选：“" + excerpt + "”。"));
                    continue;
                }
            }
            throw new IOException("本人发言记录的来源、标识、版本、时间或节选不符合契约");
        }
        return List.copyOf(matches);
    }

    /**
     * 查询当前 QQ 私聊用户的已确认称呼。
     * 只接受 {@code confirmation=operator_confirmed} 且无冲突（{@code conflict=false}）的记录；
     * 若命中多个互不一致的称呼则保守返回空，宁缺毋滥，避免把冲突身份误传给模型。
     */
    public Optional<Match> selfIdentity(Scope scope) throws IOException, InterruptedException {
        if (!scopedRecall || scope.qq().isEmpty()) {
            return Optional.empty();
        }
        long account = scope.qq().orElseThrow().peerId();
        var result = request("v1/recall", Map.of("query", "我是谁", "intent", "identity",
                "workflow", "enabled", "subject", subject(account), "limit", 12));
        var items = recallItems(result);
        var matches = new ArrayList<Match>();
        // 身份索引跨会话定位当前本人，只交付确证的称呼，不传递来源正文或其他账号资料。
        for (var raw : items) {
            if (raw instanceof JsonObject item && "ONLINE".equals(text(item, "status"))
                    && item.get("source") instanceof JsonObject source && character.equals(text(source, "character_id"))
                    && Set.of("private", "public").contains(text(source, "privacy"))
                    && item.get("identity") instanceof JsonObject identity
                    && "qq".equals(text(identity, "platform"))
                    && Long.toString(account).equals(text(identity, "account_id"))
                    && "operator_confirmed".equals(text(identity, "confirmation"))
                    && new JsonPrimitive(false).equals(identity.get("conflict"))) {
                String id = text(item, "record_id");
                String name = text(identity, "name");
                if (!id.isBlank() && !name.isBlank() && name.length() <= 128
                        && name.codePoints().noneMatch(Character::isISOControl)) {
                    matches.add(new Match(id, 0, senderNameEvidence(name) + "来源是记忆服务中经操作者确认的本人身份资料。"));
                }
            }
        }
        return matches.stream().map(Match::message).distinct().count() == 1
                ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** 两版记忆接口都按私聊发件人查称呼，给模型的证据也明确写出这个主体。 */
    static String senderNameEvidence(String name) {
        return "当前 QQ 私聊的发件人（正在与 Chat Worker 对话的用户）经确认的称呼是「" + name + "」。";
    }

    private static Map<String, String> subject(long account) {
        return Map.of("platform", "qq", "account_id", Long.toString(account));
    }

    private static JsonArray recallItems(JsonObject reply) throws IOException {
        if (!"OK".equals(text(reply, "status")) || !(reply.get("items") instanceof JsonArray items)) {
            throw new IOException("生产记忆未返回有效的召回结果");
        }
        return items;
    }

    /**
     * 校验召回候选记录是否允许在当前会话中向模型展示：
     * 核对角色、会话 ID、隐私标记以及写入来源；QQ 私聊还需核对 {@code channel} 中的平台、机器人账号与发件人，
     * 同时兼容早期未写入 {@code channel} 字段的历史 v1 生产记录（按旧写入方名称匹配）。
     */
    private boolean allowed(Scope scope, JsonObject source, String kind) {
        boolean observation = Kind.OBSERVATION.value.equals(kind);
        String writer = text(source, "source_instance");
        if ((!observation && !Kind.SELF_EXPRESSION.value.equals(kind)) || !character.equals(text(source, "character_id"))
                || !scope.conversationId().equals(text(source, "conversation_id"))
                || !Set.of("private", "public").contains(text(source, "privacy"))) {
            return false;
        }
        if (scope.qq().isEmpty()) {
            return SOURCE.equals(writer) && scope.sessionId().equals(text(source, "session_id"));
        }
        var qq = scope.qq().orElseThrow();
        String originalWriter = observation ? "onebot:" + qq.accountId() : "harness-sent:" + character;
        if (source.get("channel") instanceof JsonObject channel) {
            return (SOURCE.equals(writer) || originalWriter.equals(writer))
                    && "qq".equals(text(channel, "platform"))
                    && Long.toString(qq.accountId()).equals(text(channel, "account_id"))
                    && Long.toString(observation ? qq.peerId() : qq.accountId()).equals(text(channel, "sender_id"))
                    && qq.conversationId().equals(text(channel, "conversation_id"));
        }
        // 现有历史的 v1 记录没有 channel，按生产写入方和同一私聊 ID 核对。
        return originalWriter.equals(writer);
    }

    /** scoped 模式按跨重启稳定的 conversationId 隔离，recent 模式按当前链的 sessionId 隔离。 */
    private String conversationId(Scope scope) {
        return scopedRecall ? scope.conversationId() : scope.sessionId();
    }

    private static double epochSeconds(Instant time) {
        return time.getEpochSecond() + time.getNano() / 1_000_000_000.0;
    }

    /** 解析并验证合法 Unix 秒级时间戳（上限为 9999-12-31T23:59:59Z），无效时返回 -1。 */
    private static double occurredAt(JsonObject item) {
        if (item.get("occurred_at") instanceof JsonPrimitive field && field.isNumber()) {
            double time = field.getAsDouble();
            if (time >= 0 && time <= 253_402_300_799L) {
                return time;
            }
        }
        return -1;
    }

    /** 将校验通过的历史条目格式化为带说话人、北京时间与来源标注的自然语言引用句。 */
    private static String render(Scope scope, String kind, String content, double time) {
        boolean user = Kind.OBSERVATION.value.equals(kind);
        String speaker = user ? "用户" : "小雾";
        String channel = scope.qq().isPresent() ? "当前 QQ 私聊" : "当前控制台会话";
        return speaker + "在北京时间 " + SPOKEN_AT.format(Instant.ofEpochSecond((long) time))
                + " 说：“" + content + "”。来源是" + channel + "中已记录的" + (user ? "用户发言。" : "小雾回复。");
    }

    /** 记忆服务返回非 200 HTTP 状态时的结构化异常，保留状态码与服务端错误码供 v2 区分可重试与终态拒绝。 */
    protected static final class ServiceError extends IOException {
        final int status;
        final String code;

        ServiceError(int status, String code) {
            super("记忆服务返回 HTTP " + status + (code.isEmpty() ? "" : " " + code));
            this.status = status;
            this.code = code;
        }
    }

    /** 使用常规访问令牌向记忆服务发送 GET（{@code body=null}）或 JSON POST 请求。 */
    protected JsonObject request(String path, Object body)
            throws IOException, InterruptedException {
        return request(path, body, token);
    }

    /**
     * 使用指定 Bearer 凭证向记忆服务发送 HTTP 请求并解析严格 JSON 对象响应。
     * 供常规读写（使用 {@code token}）及 v2 原作者授权（使用独立 {@code grantToken}）共用。
     */
    protected JsonObject request(String path, Object body, String credential)
            throws IOException, InterruptedException {
        return decode(
                client.send(
                        memoryRequest(path, body, credential),
                        HttpResponse.BodyHandlers.ofString()));
    }

    /** 在群策略锁内只发起 HTTP；耗时的回执等待在锁外完成。 */
    protected java.util.concurrent.CompletableFuture<HttpResponse<String>> postAsync(
            String path, Object body) {
        return client.sendAsync(
                memoryRequest(path, body, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest memoryRequest(String path, Object body, String credential) {
        var request =
                HttpRequest.newBuilder(endpoint.resolve(path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + credential);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        }
        return request.build();
    }

    protected JsonObject decode(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            String code = "";
            try {
                var error = JSON.fromJson(response.body(), JsonObject.class);
                if (error != null && error.has("code")) code = " " + text(error, "code");
            } catch (JsonParseException ignored) {
                // 非 JSON 错误正文时忽略解析失败，仍保留 HTTP 状态码供上层判断。
            }
            throw new ServiceError(response.statusCode(), code.strip());
        }
        try {
            var result = JSON.fromJson(response.body(), JsonObject.class);
            if (result != null) {
                return result;
            }
        } catch (JsonParseException error) {
            throw new IOException("记忆服务响应须为 JSON 对象", error);
        }
        throw new IOException("记忆服务响应须为 JSON 对象");
    }

    private static String text(JsonObject source, String field) {
        return source.get(field) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    /** 读取严格正整数（1 至 18 位无前导零数字），无效或缺失时返回 0。 */
    private static long positive(JsonObject source, String field) {
        return source.get(field) instanceof JsonPrimitive value && value.isNumber()
                && value.getAsString().matches("[1-9][0-9]{0,17}") ? value.getAsLong() : 0;
    }
}
