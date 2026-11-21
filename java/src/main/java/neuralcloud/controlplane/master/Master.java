package neuralcloud.controlplane.master;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class Master {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private final HttpClient client;
    private final Settings settings;

    public record Settings(
            URI endpoint, String model, HttpClient.Version httpVersion,
            Duration connectTimeout, Duration requestTimeout,
            String probePrompt, int probeMaxTokens, double probeTemperature) {
    }

    public record Completion(String text, String finishReason) {
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
            throw new IOException("推理响应缺少非空的 choices[0].text");
        }
    }

    /** 发送并保留一轮模型的实际正文，调用者按该轮协议判断正文是否有效。 */
    public Completion complete(String prompt, int maxTokens, double temperature, List<String> stop)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", settings.model());
        payload.addProperty("prompt", prompt);
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
        JsonElement choices = result.get("choices");
        if (choices == null || !choices.isJsonArray()) {
            throw new IOException("推理响应缺少 choices 数组");
        }
        JsonArray items = choices.getAsJsonArray();
        if (items.isEmpty() || !items.get(0).isJsonObject()) {
            throw new IOException("推理响应缺少 choices[0]");
        }
        JsonObject choice = items.get(0).getAsJsonObject();
        JsonElement text = choice.get("text");
        if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
            throw new IOException("推理响应缺少字符串 choices[0].text");
        }
        return new Completion(text.getAsString(), stringField(choice, "finish_reason"));
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement field = object.get(name);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                ? field.getAsString() : "";
    }
}
