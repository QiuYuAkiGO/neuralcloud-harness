package neuralcloud.controlplane.master;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

public final class Master {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private final HttpClient client;
    private final Settings settings;

    public record Settings(
            URI endpoint, String model, HttpClient.Version httpVersion,
            Duration connectTimeout, Duration requestTimeout,
            String probePrompt, int probeMaxTokens, double probeTemperature, Boolean enableThinking) {
        public boolean usesChat() {
            return enableThinking != null;
        }
    }

    // 聊天接口的独立推理字段只保留是否存在，供挡板记录过滤事件。
    public record Completion(String text, String finishReason, boolean reasoningFieldPresent) {
    }

    public Master(HttpClient client, Settings settings) {
        this.client = client;
        this.settings = settings;
    }

    /**
     * 仅用合成提示验证指定模型的续写接口。业务判断需要等事实与印象查询能力接入。
     */
    public void probeStartup() throws IOException, InterruptedException {
        Completion completion = complete(settings.probePrompt(), settings.probeMaxTokens(), settings.probeTemperature(), List.of());
        if (completion.text().isBlank()) {
            throw new IOException("推理响应缺少非空的 " + textFieldPath());
        }
    }

    /** 发送并保留一轮模型的实际正文，调用者按该轮协议判断正文是否有效。 */
    public Completion complete(String prompt, int maxTokens, double temperature, List<String> stop)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", settings.model());
        if (settings.usesChat()) {
            payload.add("messages", JSON.toJsonTree(List.of(Map.of("role", "user", "content", prompt))));
            payload.add("chat_template_kwargs", JSON.toJsonTree(Map.of("enable_thinking", settings.enableThinking())));
        } else {
            payload.addProperty("prompt", prompt);
        }
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("temperature", temperature);
        if (!stop.isEmpty()) {
            payload.add("stop", JSON.toJsonTree(stop));
        }
        HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                .version(settings.httpVersion())
                .header("Content-Type", "application/json")
                .timeout(settings.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload)))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new IOException("推理端点请求失败（" + error.getClass().getSimpleName() + "）", error);
        }
        if (response.statusCode() != 200) {
            throw new IOException("推理端点返回 HTTP " + response.statusCode());
        }

        JsonObject result;
        try {
            result = JSON.fromJson(response.body(), JsonObject.class);
        } catch (JsonParseException error) {
            throw new IOException("推理端点未返回有效的 JSON 对象", error);
        }
        // HTTP 200 只代表请求成功，实际模型标识和续写正文才能证明探测通过。
        if (result == null || !settings.model().equals(stringField(result, "model"))) {
            throw new IOException("推理响应中的 model 与配置不符");
        }
        if (!(result.get("choices") instanceof JsonArray choices) || choices.isEmpty()
                || !(choices.get(0) instanceof JsonObject choice)) {
            throw invalidText();
        }
        JsonObject body = choice;
        String fieldName = "text";
        if (settings.usesChat()) {
            if (!(choice.get("message") instanceof JsonObject message)) {
                throw invalidText();
            }
            body = message;
            fieldName = "content";
        }
        boolean reasoningPresent = settings.usesChat() && !stringField(body, "reasoning_content").isBlank();
        JsonElement content = body.get(fieldName);
        String finishReason = stringField(choice, "finish_reason");
        if (content instanceof JsonPrimitive text && text.isString()) {
            return new Completion(text.getAsString(), finishReason, reasoningPresent);
        }
        if (reasoningPresent && (content == null || content.isJsonNull())) {
            return new Completion("", finishReason, true);
        }
        throw invalidText();
    }

    private IOException invalidText() {
        return new IOException("推理响应不符合 " + textFieldPath() + " 字符串契约");
    }

    private String textFieldPath() {
        return settings.usesChat() ? "choices[0].message.content" : "choices[0].text";
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement field = object.get(name);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                ? field.getAsString() : "";
    }
}
