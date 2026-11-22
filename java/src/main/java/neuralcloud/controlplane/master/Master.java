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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Master {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private final HttpClient client;
    private final Settings settings;

    public record Settings(
            URI endpoint,
            String model,
            HttpClient.Version httpVersion,
            Duration connectTimeout,
            Duration requestTimeout,
            String probePrompt,
            int probeMaxTokens,
            double probeTemperature,
            Boolean enableThinking) {
        public boolean usesChat() {
            return enableThinking != null;
        }
    }

    // 聊天接口的独立推理字段只保留是否存在，供挡板记录过滤事件。
    public record Completion(String text, String finishReason, boolean reasoningFieldPresent) {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    public record ChatTurn(Completion content, List<ToolCall> calls) {
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
        if (settings.usesChat()) {
            payload.add("messages", JSON.toJsonTree(List.of(Map.of("role", "user", "content", prompt))));
            payload.add("chat_template_kwargs", JSON.toJsonTree(Map.of("enable_thinking", settings.enableThinking())));
        } else {
            payload.addProperty("prompt", prompt);
        }
        if (!stop.isEmpty()) {
            payload.add("stop", JSON.toJsonTree(stop));
        }
        JsonObject choice = request(payload, maxTokens, temperature);
        JsonObject message = settings.usesChat() ? chatMessage(choice) : choice;
        return completion(choice, message, false);
    }

    /** 多轮聊天只从原生 tool_calls 读取动作，普通正文不会被执行。 */
    public ChatTurn chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                         int maxTokens, double temperature) throws IOException, InterruptedException {
        return chat(messages, tools, maxTokens, temperature, null);
    }

    public ChatTurn chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                         int maxTokens, double temperature, String requiredTool) throws IOException, InterruptedException {
        if (!settings.usesChat()) {
            throw new IOException("工具调用需要配置 /v1/chat/completions");
        }
        JsonObject payload = new JsonObject();
        payload.add("messages", JSON.toJsonTree(messages));
        payload.add("tools", JSON.toJsonTree(tools));
        if (requiredTool != null) {
            payload.add("tool_choice", JSON.toJsonTree(Map.of("type", "function", "function", Map.of("name", requiredTool))));
        }
        payload.addProperty("parallel_tool_calls", false);
        payload.add("chat_template_kwargs", JSON.toJsonTree(Map.of("enable_thinking", settings.enableThinking())));
        JsonObject choice = request(payload, maxTokens, temperature);
        JsonObject message = chatMessage(choice);
        List<ToolCall> calls = new ArrayList<>();
        JsonElement toolCalls = message.get("tool_calls");
        if (toolCalls != null && !toolCalls.isJsonNull()) {
            if (!(toolCalls instanceof JsonArray array)) {
                throw new IOException("推理响应不符合 message.tool_calls 数组契约");
            }
            for (JsonElement item : array) {
                if (!(item instanceof JsonObject call) || !"function".equals(stringField(call, "type"))
                        || stringField(call, "id").isBlank()
                        || !(call.get("function") instanceof JsonObject function)
                        || stringField(function, "name").isBlank()
                        || !(function.get("arguments") instanceof JsonPrimitive arguments) || !arguments.isString()) {
                    throw new IOException("推理响应缺少有效的工具调用标识、函数名或参数");
                }
                calls.add(new ToolCall(stringField(call, "id"), stringField(function, "name"), arguments.getAsString()));
            }
        }
        return new ChatTurn(completion(choice, message, true), List.copyOf(calls));
    }

    private JsonObject request(JsonObject payload, int maxTokens, double temperature)
            throws IOException, InterruptedException {
        payload.addProperty("model", settings.model());
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("temperature", temperature);
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
        return choice;
    }

    private JsonObject chatMessage(JsonObject choice) throws IOException {
        if (choice.get("message") instanceof JsonObject message) {
            return message;
        }
        throw invalidText();
    }

    private Completion completion(JsonObject choice, JsonObject body, boolean allowEmptyChat) throws IOException {
        boolean reasoningPresent = settings.usesChat() && !stringField(body, "reasoning_content").isBlank();
        JsonElement content = body.get(settings.usesChat() ? "content" : "text");
        String finishReason = stringField(choice, "finish_reason");
        if (content instanceof JsonPrimitive text && text.isString()) {
            return new Completion(text.getAsString(), finishReason, reasoningPresent);
        }
        if ((reasoningPresent || allowEmptyChat) && (content == null || content.isJsonNull())) {
            return new Completion("", finishReason, reasoningPresent);
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
