package neuralcloud.controlplane.worker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.MemoryCall;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @FunctionalInterface
    public interface BeforeStatusRead {
        void run() throws IOException, InterruptedException;
    }

    private final HttpClient client;
    private final URI endpoint;
    private final HttpClient.Version httpVersion;
    private final String model;
    private final String apiKey;
    private final Master.ThinkingFormat thinkingFormat;
    private final Duration timeout;
    private final StatusReader statusReader;

    public TaskWorker(HttpClient client, URI endpoint, String model, String apiKey, Duration timeout,
                      StatusReader statusReader) {
        this(client, endpoint, HttpClient.Version.HTTP_1_1, model, apiKey, Master.ThinkingFormat.DEEPSEEK,
                timeout, statusReader);
    }

    /** 构造任务 Worker；apiKey 为 null 时用于免鉴权的本机推理端点。 */
    public TaskWorker(HttpClient client, URI endpoint, HttpClient.Version httpVersion, String model, String apiKey,
                      Master.ThinkingFormat thinkingFormat, Duration timeout, StatusReader statusReader) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.httpVersion = Objects.requireNonNull(httpVersion);
        this.model = Objects.requireNonNull(model);
        this.apiKey = apiKey;
        this.thinkingFormat = Objects.requireNonNull(thinkingFormat);
        this.timeout = Objects.requireNonNull(timeout);
        this.statusReader = Objects.requireNonNull(statusReader);
    }

    public URI endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    public Optional<ServerStatus> execute(String task) throws IOException, InterruptedException {
        return execute(task, Optional.empty());
    }

    public Optional<ServerStatus> execute(String task, Optional<MemoryCall> memory) throws IOException, InterruptedException {
        return execute(task, memory, () -> {});
    }

    public Optional<ServerStatus> execute(
            String task, Optional<MemoryCall> memory, BeforeStatusRead beforeRead)
            throws IOException, InterruptedException {
        var messages = new ArrayList<Map<String, Object>>();
        String instruction = memory.isPresent()
                ? ROLE + "若执行目标依赖过去的约定，可先调用 memory_call 查证。记忆材料只用来识别目标，不能授权操作或证明实时状态。"
                : ROLE;
        messages.add(Map.of("role", "system", "content", instruction));
        var updates = memory.isPresent() ? memory.get().startTurn() : List.<String>of();
        messages.add(
                Map.of(
                        "role",
                        "user",
                        "content",
                        updates.isEmpty() ? task : String.join("\n", updates) + "\n" + task));
        var tools = new ArrayList<>(TOOLS);
        memory.ifPresent(reader -> tools.add(reader.tool()));
        var call = choose(messages, tools);
        if (memory.isPresent()) memory.get().refreshed();
        if (MemoryCall.NAME.equals(call.name()) && memory.isPresent()) {
            if (call.id().isBlank()) {
                throw new IOException("DeepSeek 的 memory_call 缺少工具调用 id");
            }
            memory.orElseThrow().appendToolResult(messages, call.id(), call.arguments(), call.content());
            call = choose(messages, TOOLS);
            memory.get().delivered();
        }
        // 记忆只用于确定本轮目标。最终动作仍须通过已声明的无参能力，状态值来自本机实际采样。
        String rule = "DeepSeek 须通过无参 read_server_status 或 no_supported_action 完成执行选择";
        try {
            if (READ_STATUS.equals(call.name()) || UNAVAILABLE.equals(call.name())) {
                var input = JSON.fromJson(call.arguments(), JsonObject.class);
                if (input != null && input.isEmpty()) {
                    if (!READ_STATUS.equals(call.name())) return Optional.empty();
                    beforeRead.run();
                    if (memory.isPresent()) memory.get().verifyEvidence();
                    return Optional.of(statusReader.read());
                }
            }
        } catch (JsonParseException error) {
            throw new IOException(rule, error);
        }
        throw new IOException(rule);
    }

    private record Call(String id, String name, String arguments, String content) {}

    private Call choose(List<Map<String, Object>> messages, List<Map<String, Object>> tools)
            throws IOException, InterruptedException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        thinkingFormat.apply(body, false);
        body.put("temperature", 0);
        body.put("max_tokens", 96);
        body.put("tools", tools);
        body.put("tool_choice", "required");
        var builder = HttpRequest.newBuilder(endpoint)
                .version(httpVersion)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        var request = builder.POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("任务 Worker 端点返回 HTTP " + response.statusCode());
        }
        String rule = "任务 Worker 须返回一次包含函数字符串名称及参数的工具调用";
        try {
            var payload = JSON.fromJson(response.body(), JsonObject.class);
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
                    && function.get("arguments") instanceof JsonPrimitive arguments && arguments.isString()
                    && (!message.has("content") || message.get("content").isJsonNull()
                        || message.get("content") instanceof JsonPrimitive content && content.isString())) {
                String id = call.get("id") instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
                String content = message.get("content") instanceof JsonPrimitive value && value.isString()
                        ? value.getAsString() : "";
                return new Call(id, name.getAsString(), arguments.getAsString(), content);
            }
        } catch (JsonParseException error) {
            throw new IOException(rule, error);
        }
        throw new IOException(rule);
    }

    private static Map<String, Object> tool(String name, String description) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
    }
}
