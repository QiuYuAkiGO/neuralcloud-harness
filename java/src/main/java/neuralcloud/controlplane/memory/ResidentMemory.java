package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.ConsoleServer.Trace.Scope;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ResidentMemory {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String SOURCE = "neuralcloud-java";
    private static final Set<String> UNAVAILABLE = Set.of(
            "NOT_FOUND", "ARCHIVED_OFFLINE", "RETRACTED", "SOURCE_CHANGED", "INACTIVE");

    public enum Kind {
        OBSERVATION("observation"), SELF_EXPRESSION("self_expression");

        private final String value;

        Kind(String value) {
            this.value = value;
        }
    }

    public record Receipt(String recordId, long revision, long sequence) {}
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

    boolean acceptsReceipt(JsonObject event) {
        return (!scopedRecall && !event.has("characterId")) || character.equals(text(event, "characterId"));
    }

    public void verify() throws IOException, InterruptedException {
        var result = request("v1/health", null);
        if (!character.equals(text(result, "character_id")) || !"TEXT_READY".equals(text(result, "status"))) {
            throw new IOException("记忆服务未就绪或 character_id 与配置不符");
        }
        if (scopedRecall && (!(result.get("workflow") instanceof JsonObject)
                || !(result.get("source_event_versions") instanceof JsonArray versions)
                || !versions.contains(new JsonPrimitive(2)))) {
            throw new IOException("生产记忆服务须支持按会话检索和来源事件 v2");
        }
    }

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

    String character() {
        return character;
    }

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
        if (!"ONLINE".equals(status) || positive(reply, "revision") == 0 || content.isBlank()
                || !(reply.get("source") instanceof JsonObject source)
                || !character.equals(text(source, "character_id")) || !SOURCE.equals(text(source, "source_instance"))
                || !scope.sessionId().equals(text(source, "session_id"))
                || !conversationId(scope).equals(text(source, "conversation_id"))
                || !"private".equals(text(source, "privacy")) || !kind.equals(text(source, "kind"))
                || !(Kind.OBSERVATION.value.equals(kind) || Kind.SELF_EXPRESSION.value.equals(kind))) {
            throw new IOException("记忆记录不符合当前会话的来源或内容契约");
        }
        return Optional.of(render(kind, content));
    }

    public List<Match> search(Scope scope, String query, Instant receivedAt) throws IOException, InterruptedException {
        // Recall 的最大查询长度是 256 个 Unicode 字符。长消息仍有按 ID 重读的近期对话。
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
                if ("ONLINE".equals(text(item, "status")) && !id.isBlank() && id.length() <= 256
                        && !content.isBlank() && positive(item, "revision") > 0
                        && item.get("occurred_at") instanceof JsonPrimitive time && time.isNumber()
                        && time.getAsDouble() >= 0 && time.getAsDouble() <= until
                        && kind.equals(text(source, "kind")) && allowed(scope, source, kind)) {
                    matches.add(new Match(id, time.getAsDouble(), render(kind, content)));
                }
            }
        }
        return matches.stream().distinct().limit(4).sorted(Comparator.comparingDouble(Match::occurredAt)).toList();
    }

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
                    matches.add(new Match(id, 0, "当前 QQ 账号经确认的称呼是「" + name + "」。"));
                }
            }
        }
        return matches.stream().map(Match::message).distinct().count() == 1
                ? Optional.of(matches.getFirst()) : Optional.empty();
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

    private String conversationId(Scope scope) {
        return scopedRecall ? scope.conversationId() : scope.sessionId();
    }

    private static double epochSeconds(Instant time) {
        return time.getEpochSecond() + time.getNano() / 1_000_000_000.0;
    }

    private static String render(String kind, String content) {
        return (Kind.OBSERVATION.value.equals(kind) ? "用户说：" : "小雾说：") + content;
    }

    private JsonObject request(String path, Map<String, ?> body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("记忆服务返回 HTTP " + response.statusCode());
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

    private static long positive(JsonObject source, String field) {
        return source.get(field) instanceof JsonPrimitive value && value.isNumber()
                && value.getAsString().matches("[1-9][0-9]{0,17}") ? value.getAsLong() : 0;
    }
}
