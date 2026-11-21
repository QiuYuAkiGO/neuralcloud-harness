package neuralcloud.controlplane.worker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatWorker {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private final HttpClient client;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maxOutputTokens;
    private final String thinkingLevel;
    private final String apiKey;
    private final String rolePrompt;

    public ChatWorker(HttpClient client, URI endpoint, Duration requestTimeout, int maxOutputTokens,
                      String thinkingLevel, String apiKey, String rolePrompt) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingLevel = Objects.requireNonNull(thinkingLevel);
        this.apiKey = Objects.requireNonNull(apiKey);
        this.rolePrompt = Objects.requireNonNull(rolePrompt);
    }

    public String draftReply(String approvedText) throws IOException {
        if (approvedText.isBlank()) {
            throw new IllegalArgumentException("Chat Worker 需要非空的获准回复正文");
        }
        // 编排元数据与前置回执留在 Java。Gemini 只接收当前步骤允许传达的正文。
        var body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", rolePrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", approvedText)))),
                "generationConfig", Map.of("maxOutputTokens", maxOutputTokens,
                        "thinkingConfig", Map.of("thinkingLevel", thinkingLevel, "includeThoughts", false)));
        var request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Gemini 请求被中断", error);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Gemini 返回 HTTP " + response.statusCode());
        }
        try {
            return replyText(JSON.fromJson(response.body(), JsonObject.class));
        } catch (JsonParseException error) {
            throw new IOException("Gemini 响应不是有效的 JSON 对象", error);
        }
    }

    private static String replyText(JsonObject payload) throws IOException {
        if (payload == null || !(payload.get("candidates") instanceof JsonArray candidates)
                || candidates.size() != 1 || !(candidates.get(0) instanceof JsonObject candidate)
                || !new JsonPrimitive("STOP").equals(candidate.get("finishReason"))
                || !(candidate.get("content") instanceof JsonObject content)
                || !(content.get("parts") instanceof JsonArray parts)) {
            throw new IOException("Gemini 响应须有一条正常结束的文本候选");
        }
        var reply = new StringBuilder();
        for (var item : parts) {
            if (!(item instanceof JsonObject part) || !(part.get("text") instanceof JsonPrimitive text)
                    || !text.isString()) {
                throw new IOException("Gemini 回复只接受文本片段");
            }
            // 思考片段与可见回复分开，草稿也不构成渠道送达回执。
            if (!new JsonPrimitive(true).equals(part.get("thought"))) {
                reply.append(text.getAsString());
            }
        }
        if (reply.toString().isBlank()) {
            throw new IOException("Gemini 未返回可见的回复正文");
        }
        return reply.toString().strip();
    }
}
