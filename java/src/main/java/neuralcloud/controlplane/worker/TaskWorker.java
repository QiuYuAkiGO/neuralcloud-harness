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
import java.util.Optional;

public final class TaskWorker {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String READ_STATUS = "read_server_status";
    private static final String UNAVAILABLE = "no_supported_action";
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool(READ_STATUS, "读取运行本控制台的 Linux 服务器的当前负载、运行时间、内存和根分区容量。"),
            tool(UNAVAILABLE, "请求需要其他服务器、其他数据、其他操作，或执行目标不明确。"));
    private static final String ROLE = """
            按用户要求选择一项工具。只在用户要求查看当前这台 Linux 服务器的状态时调用 read_server_status。
            如指定其他主机、超出工具描述或需要修改系统，调用 no_supported_action。不要输出状态或执行结果。
            """;

    @FunctionalInterface
    public interface StatusReader {
        ServerStatus read() throws IOException;
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final StatusReader statusReader;

    public TaskWorker(HttpClient client, URI endpoint, String model, String apiKey, Duration timeout,
                      StatusReader statusReader) {
        this.client = client;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.statusReader = statusReader;
    }

    public String model() {
        return model;
    }

    public Optional<ServerStatus> execute(String task) throws IOException, InterruptedException {
        var body = Map.of("model", model, "messages", List.of(Map.of("role", "system", "content", ROLE),
                        Map.of("role", "user", "content", task)),
                "thinking", Map.of("type", "disabled"), "temperature", 0, "max_tokens", 96,
                "tools", TOOLS, "tool_choice", "required");
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("任务 Worker 的 DeepSeek 端点返回 HTTP " + response.statusCode());
        }
        String tool = toolName(response.body());
        // 模型只能选择当前主机的无参读取。读取成功后，实际采样值才构成回执。
        return READ_STATUS.equals(tool) ? Optional.of(statusReader.read()) : Optional.empty();
    }

    private String toolName(String body) throws IOException {
        try {
            var payload = JSON.fromJson(body, JsonObject.class);
            if (payload != null && new JsonPrimitive(model).equals(payload.get("model"))
                    && payload.get("choices") instanceof JsonArray choices && choices.size() == 1
                    && choices.get(0) instanceof JsonObject choice
                    && new JsonPrimitive("tool_calls").equals(choice.get("finish_reason"))
                    && choice.get("message") instanceof JsonObject message
                    && message.get("tool_calls") instanceof JsonArray calls && calls.size() == 1
                    && calls.get(0) instanceof JsonObject call
                    && new JsonPrimitive("function").equals(call.get("type"))
                    && call.get("function") instanceof JsonObject function
                    && function.get("name") instanceof JsonPrimitive name && name.isString()
                    && (READ_STATUS.equals(name.getAsString()) || UNAVAILABLE.equals(name.getAsString()))
                    && function.get("arguments") instanceof JsonPrimitive arguments && arguments.isString()) {
                var input = JSON.fromJson(arguments.getAsString(), JsonObject.class);
                if (input != null && input.isEmpty()) {
                    return name.getAsString();
                }
            }
        } catch (JsonParseException error) {
            throw new IOException("DeepSeek 必须返回一次已声明的无参工具调用", error);
        }
        throw new IOException("DeepSeek 必须返回一次已声明的无参工具调用");
    }

    private static Map<String, Object> tool(String name, String description) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
    }
}
