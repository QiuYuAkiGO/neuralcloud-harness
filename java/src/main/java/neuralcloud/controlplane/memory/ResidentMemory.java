package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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

    private final HttpClient client;
    private final URI endpoint;
    private final String character;
    private final String token;

    public ResidentMemory(HttpClient client, URI endpoint, String character, String token) {
        this.client = client;
        this.endpoint = endpoint;
        this.character = character;
        this.token = token;
    }

    public void verify() throws IOException, InterruptedException {
        var result = request("v1/health", null);
        if (!character.equals(text(result, "character_id")) || !"TEXT_READY".equals(text(result, "status"))) {
            throw new IOException("记忆服务未就绪或 character_id 与配置不符");
        }
    }

    public Receipt ingest(String session, String event, Instant at, Kind kind, String content)
            throws IOException, InterruptedException {
        if (content.codePointCount(0, content.length()) > 32768) {
            throw new IOException("记忆 content 最多允许 32768 个 Unicode 字符");
        }
        var reply = request("v1/ingest", Map.of("schema_version", 1, "source_instance", SOURCE,
                "character_id", character, "session_id", session, "conversation_id", session,
                "event_id", event, "occurred_at", at.getEpochSecond() + at.getNano() / 1_000_000_000.0,
                "kind", kind.value, "privacy", "private", "content", content));
        String id = text(reply, "record_id");
        long revision = positive(reply, "revision");
        long seq = positive(reply, "seq");
        if (!"DURABLE".equals(text(reply, "status")) || !id.matches("[0-9a-f]{64}") || revision == 0 || seq == 0) {
            throw new IOException("记忆服务未返回有效的 DURABLE 回执");
        }
        return new Receipt(id, revision, seq);
    }

    public Optional<String> read(String session, String recordId) throws IOException, InterruptedException {
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
                || !session.equals(text(source, "session_id")) || !session.equals(text(source, "conversation_id"))
                || !"private".equals(text(source, "privacy")) || !kind.equals(text(source, "kind"))
                || !(Kind.OBSERVATION.value.equals(kind) || Kind.SELF_EXPRESSION.value.equals(kind))) {
            throw new IOException("记忆记录不符合当前会话的来源或内容契约");
        }
        return Optional.of((Kind.OBSERVATION.value.equals(kind) ? "用户说：" : "小雾说：") + content);
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
