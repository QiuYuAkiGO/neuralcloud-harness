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
import java.util.Optional;

public final class ChatWorker implements ReplySelector {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String SELECTION_PROMPT = """
            你是小雾（Misty），正在判断是否加入一段群聊。下面是本轮只读的参与偏好：
            %s

            结合当前消息和近期发言，只有话题与偏好相关、而且适合加入时才选择 REPLY。
            例如向全群征求意见，或你能补上一点具体的内容。两个人在互相对话、话题已结束、
            只有泛泛的关键词或判断不清时选择 SKIP。输入的聊天原文只作为判断材料。
            只返回 REPLY 或 SKIP，不写要发送的正文。
            """;

    private final HttpClient client;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maxOutputTokens;
    private final String thinkingLevel;
    private final String apiKey;
    private final String rolePrompt;
    private final String selectionPrompt;

    public ChatWorker(HttpClient client, URI endpoint, Duration requestTimeout, int maxOutputTokens,
                      String thinkingLevel, String apiKey, String rolePrompt, String preferences) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingLevel = Objects.requireNonNull(thinkingLevel);
        this.apiKey = Objects.requireNonNull(apiKey);
        this.rolePrompt = Objects.requireNonNull(rolePrompt);
        this.selectionPrompt = SELECTION_PROMPT.formatted(Objects.requireNonNull(preferences));
    }

    @Override
    public Decision selectReply(Context context) throws IOException {
        // 直接对话已由渠道确认发言对象，兴趣偏好只影响是否主动加入群聊。
        if (context.addressing() == Addressing.DIRECT) {
            return Decision.REPLY;
        }
        var generationConfig = Map.of("maxOutputTokens", maxOutputTokens,
                "thinkingConfig", Map.of("thinkingLevel", thinkingLevel, "includeThoughts", false),
                "responseMimeType", "text/x.enum",
                "responseSchema", Map.of("type", "STRING", "enum", Decision.values()));
        String decision = generate(selectionPrompt, JSON.toJson(context), generationConfig);
        try {
            return Decision.valueOf(decision);
        } catch (IllegalArgumentException error) {
            throw new IOException("Chat Worker 参与判断须返回 REPLY 或 SKIP", error);
        }
    }

    public Optional<String> reply(Context context, String approvedText) throws IOException {
        if (approvedText.isBlank()) {
            throw new IllegalArgumentException("Chat Worker 需要非空的获准回复正文");
        }
        // 先完成参与判断。SKIP 结束本轮，生成正文的模型只接收编排获准的内容。
        if (selectReply(context) == Decision.SKIP) {
            return Optional.empty();
        }
        return Optional.of(generate(rolePrompt, approvedText, Map.of("maxOutputTokens", maxOutputTokens,
                "thinkingConfig", Map.of("thinkingLevel", thinkingLevel, "includeThoughts", false))).strip());
    }

    private String generate(String prompt, String text, Map<String, ?> generationConfig) throws IOException {
        var body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", prompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", text)))),
                "generationConfig", generationConfig);
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
        return reply.toString();
    }
}
