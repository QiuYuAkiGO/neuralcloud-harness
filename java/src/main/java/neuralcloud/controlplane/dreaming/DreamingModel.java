package neuralcloud.controlplane.dreaming;

import com.google.gson.*;

import neuralcloud.controlplane.integration.inference.InvalidModelOutput;
import neuralcloud.controlplane.worker.ChatWorker;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Dreaming 独占的推理客户端。模型只见材料与整理契约，没有读取私聊、写账或调度 Master 的句柄。 */
public final class DreamingModel implements AutoCloseable {
    private enum Protocol {
        GEMINI,
        OPENAI_CHAT
    }

    private static final Gson JSON =
            new GsonBuilder().setStrictness(Strictness.STRICT).disableHtmlEscaping().create();
    private static final String TOOL = "submit_dreaming";
    private static final Set<String> CHANGE_FIELDS = Set.of(
            "kind", "subject", "expected_version", "text", "scope", "reason", "source_refs");
    private static final Set<String> REQUIRED =
            Set.of(
                    "protocol",
                    "endpoint",
                    "model",
                    "connectTimeoutSeconds",
                    "requestTimeoutSeconds",
                    "maxOutputTokens",
                    "batchSize",
                    "intervalSeconds");
    private final Protocol protocol;
    private final URI endpoint;
    private final String model, key, prompt;
    private final JsonObject schema;
    private final HttpClient client;
    private final Duration timeout;
    private final int tokens, batchSize, interval;
    private final Set<String> scopes;
    private final Optional<Path> directory;

    /** 一次校验独立配置，在启动时锁定协议和凭据来源。 */
    public static DreamingModel open(Properties properties) throws IOException {
        return new DreamingModel(properties);
    }

    private DreamingModel(Properties p) throws IOException {
        var names = new HashSet<>(p.stringPropertyNames());
        names.removeAll(Set.of("apiKeyEnv", "proxy", "sourceScopes", "outputDirectory"));
        if (!names.equals(REQUIRED)
                || p.stringPropertyNames().stream().anyMatch(k -> p.getProperty(k).isBlank()))
            throw new IllegalArgumentException("Dreaming 配置字段须非空并符合 " + REQUIRED);
        protocol = Protocol.valueOf(p.getProperty("protocol"));
        model = p.getProperty("model");
        endpoint = URI.create(p.getProperty("endpoint"));
        boolean localHttp =
                "http".equals(endpoint.getScheme())
                        && endpoint.getHost() != null
                        && (endpoint.getHost().matches("127(\\.[0-9]{1,3}){3}")
                                || Set.of("localhost", "[::1]").contains(endpoint.getHost()));
        boolean validPath =
                protocol == Protocol.GEMINI
                        ? Objects.equals(
                                endpoint.getPath(), "/v1beta/models/" + model + ":generateContent")
                        : endpoint.getPath() != null
                                && endpoint.getPath().endsWith("/chat/completions");
        boolean compatibleHttp =
                protocol == Protocol.OPENAI_CHAT && "http".equals(endpoint.getScheme());
        if ((!localHttp && !compatibleHttp && !"https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getPort() == 0
                || endpoint.getPort() > 65535
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !validPath)
            throw new IllegalArgumentException("Dreaming endpoint 须为所选协议的完整模型地址");
        String env = p.getProperty("apiKeyEnv");
        key = env == null ? null : System.getenv(env);
        if ((protocol == Protocol.GEMINI || env != null) && (key == null || key.isBlank()))
            throw new IllegalArgumentException("Dreaming 缺少 apiKeyEnv 凭据 " + env);
        batchSize = positive(p, "batchSize", 16);
        interval = positive(p, "intervalSeconds", Integer.MAX_VALUE);
        tokens = positive(p, "maxOutputTokens", Integer.MAX_VALUE);
        timeout = Duration.ofSeconds(positive(p, "requestTimeoutSeconds", Integer.MAX_VALUE));
        var selected = new TreeSet<String>();
        if (p.containsKey("sourceScopes")) {
            for (String scope : p.getProperty("sourceScopes").split(",", -1)) {
                if (scope.isBlank()
                        || !scope.equals(scope.strip())
                        || scope.length() > 256
                        || !selected.add(scope)
                        || scope.startsWith("c2c:onebot:")
                        || scope.startsWith("group:onebot:"))
                    throw new IllegalArgumentException("sourceScopes 须为不重复的非 QQ 精确会话 ID，QQ 由渠道授权");
            }
        }
        scopes = Set.copyOf(selected);
        directory = Optional.ofNullable(p.getProperty("outputDirectory")).map(Path::of);
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(
                                        positive(p, "connectTimeoutSeconds", Integer.MAX_VALUE)));
        if (p.containsKey("proxy")) {
            var proxy = URI.create("http://" + p.getProperty("proxy"));
            if (proxy.getHost() == null
                    || proxy.getPort() < 1
                    || proxy.getPort() > 65535
                    || !proxy.getRawPath().isEmpty()
                    || proxy.getRawUserInfo() != null
                    || proxy.getRawQuery() != null
                    || proxy.getRawFragment() != null)
                throw new IllegalArgumentException("Dreaming proxy 须为 host:port");
            http.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        prompt = resource("dreaming-prompt.txt");
        schema = JSON.fromJson(resource("dreaming-schema.json"), JsonObject.class);
        client = http.build();
    }

    private static String resource(String name) throws IOException {
        try (var input = DreamingModel.class.getResourceAsStream("/config/" + name)) {
            if (input == null) throw new IOException("缺少 Dreaming 资源 " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int positive(Properties p, String name, int max) {
        int value;
        try {
            value = Integer.parseInt(p.getProperty(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 须为整数", e);
        }
        if (value < 1 || value > max) throw new IllegalArgumentException(name + " 超出范围");
        return value;
    }

    public Set<String> sourceScopes() {
        return scopes;
    }

    public Optional<Path> outputDirectory() {
        return directory;
    }

    public int batchSize() {
        return batchSize;
    }

    public int intervalSeconds() {
        return interval;
    }

    /** 屏蔽回执和水位等宿主字段，两个协议共用同一组一手依据、提示词和输出校验。 */
    public JsonArray organize(JsonObject batch) throws IOException, InterruptedException {
        return organize(batch, null);
    }

    /** 每次只发一个请求。补正由调度器按已持久化的错误发起，继续核对同一批证据。 */
    JsonArray organize(JsonObject batch, String correction) throws IOException, InterruptedException {
        var material = new JsonObject();
        material.add("evidence", batch.getAsJsonArray("evidence"));
        material.add("profiles", batch.getAsJsonArray("profiles"));
        if (correction != null) material.addProperty("validation_error", correction);
        String input = JSON.toJson(material);
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json");
        Object payload;
        if (protocol == Protocol.GEMINI) {
            request.header("x-goog-api-key", key);
            payload =
                    Map.of(
                            "systemInstruction",
                            Map.of("parts", List.of(Map.of("text", prompt))),
                            "contents",
                            List.of(
                                    Map.of(
                                            "role",
                                            "user",
                                            "parts",
                                            List.of(Map.of("text", input)))),
                            "generationConfig",
                            Map.of(
                                    "responseMimeType",
                                    "application/json",
                                    "responseJsonSchema",
                                    schema,
                                    "maxOutputTokens",
                                    tokens,
                                    "thinkingConfig",
                                    Map.of("thinkingLevel", "LOW")));
        } else {
            if (key != null) request.header("Authorization", "Bearer " + key);
            payload =
                    Map.of(
                            "model",
                            model,
                            "max_tokens",
                            tokens,
                            "temperature",
                            0,
                            "parallel_tool_calls",
                            false,
                            "messages",
                            List.of(
                                    Map.of("role", "system", "content", prompt),
                                    Map.of("role", "user", "content", input)),
                            "tools",
                            List.of(
                                    Map.of(
                                            "type",
                                            "function",
                                            "function",
                                            Map.of("name", TOOL, "parameters", schema))),
                            "tool_choice",
                            Map.of("type", "function", "function", Map.of("name", TOOL)));
        }
        var reply =
                client.send(
                        request.POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (reply.statusCode() != 200)
            throw new IOException("Dreaming " + protocol + " HTTP " + reply.statusCode());
        try {
            var envelope = JSON.fromJson(reply.body(), JsonObject.class);
            String arguments =
                    protocol == Protocol.GEMINI
                            ? ChatWorker.geminiText(envelope)
                            : toolArguments(envelope);
            return checked(JSON.fromJson(arguments, JsonObject.class), batch);
        } catch (IOException invalid) {
            throw new InvalidModelOutput(invalid.getMessage(), invalid);
        } catch (JsonParseException | IllegalStateException | NumberFormatException invalid) {
            throw new InvalidModelOutput("Dreaming 模型响应须为符合 schema 的完整 JSON 对象", invalid);
        }
    }

    /** 工具模式只接受目标函数的完整单次调用。 */
    private static String toolArguments(JsonObject reply) throws IOException {
        var choice = only(reply, "choices");
        String finish = string(choice, "finish_reason", 32);
        if (!Set.of("tool_calls", "stop").contains(finish))
            throw invalid("finish_reason 须为 tool_calls 或 stop，实际为 " + finish);
        var message = object(choice, "message");
        var call = only(message, "tool_calls");
        var function = object(call, "function");
        String name = string(function, "name", 64);
        if (!"function".equals(string(call, "type", 32)) || !TOOL.equals(name))
            throw invalid("tool_calls 须调用 function " + TOOL + "，实际工具名 " + name);
        return string(function, "arguments", Integer.MAX_VALUE);
    }

    private static JsonObject only(JsonObject o, String k) throws IOException {
        if (o != null
                && o.get(k) instanceof JsonArray a
                && a.size() == 1
                && a.get(0) instanceof JsonObject item) return item;
        throw invalid(k + " 须为仅含一个对象的数组，实际为 " + shape(o == null ? null : o.get(k)));
    }

    private static JsonObject object(JsonObject o, String key) throws IOException {
        if (o.get(key) instanceof JsonObject value) return value;
        throw invalid(key + " 须为对象，实际为 " + shape(o.get(key)));
    }

    /** 模型签入同一来源只能一次，旧印象与模型自报署名均不能成为证据。 */
    private static JsonArray checked(JsonObject output, JsonObject batch) throws IOException {
        if (output == null
                || !output.keySet().equals(Set.of("changes"))
                || !(output.get("changes") instanceof JsonArray changes)
                || changes.size() > 8) throw invalid("根对象须只有 changes，值须为含 0–8 项的数组");
        var offered = new HashMap<String, Long>();
        for (var entry : batch.getAsJsonArray("evidence")) {
            var evidence = entry.getAsJsonObject();
            offered.put(
                    evidence.get("record_id").getAsString(), evidence.get("revision").getAsLong());
        }
        var versions = new HashMap<List<String>, Long>();
        for (var entry : batch.getAsJsonArray("profiles")) {
            var profile = entry.getAsJsonObject();
            versions.put(
                    List.of(
                            profile.get("kind").getAsString(),
                            profile.get("subject").getAsString()),
                    profile.get("version").getAsLong());
        }
        var identities = new HashSet<List<String>>();
        for (int index = 0; index < changes.size(); index++) {
            var entry = changes.get(index);
            String path = "changes[" + index + "]";
            if (!(entry instanceof JsonObject item) || !item.keySet().equals(CHANGE_FIELDS))
                throw invalid(path
                            + " 须恰有 kind、subject、expected_version、text、scope、reason、source_refs，实际为 "
                            + (entry instanceof JsonObject row ? row.keySet() : shape(entry)));
            String kind = string(item, "kind", 16);
            var identity = List.of(kind, string(item, "subject", 128));
            if (!Set.of("person", "topic").contains(kind) || !identities.add(identity))
                throw invalid(path + " 的 kind 须为 person 或 topic，同一 kind/subject 每批仅可出现一次");
            long expected = versions.getOrDefault(identity, 0L);
            long submittedVersion = integer(item, "expected_version", 0);
            if (submittedVersion != expected)
                throw invalid(path + ".expected_version 应为 " + expected + "，实际 " + submittedVersion);
            if (!string(item, "text", 2048).startsWith("我")) throw invalid("text 须以我开头");
            string(item, "scope", 512);
            string(item, "reason", 1024);
            if (!(item.get("source_refs") instanceof JsonArray refs)
                    || refs.isEmpty()
                    || refs.size() > 16)
                throw invalid(path + ".source_refs 须为含 1–16 条来源的数组，实际为 " + shape(item.get("source_refs")));
            var seen = new HashSet<String>();
            for (var raw : refs) {
                if (!(raw instanceof JsonObject ref)
                        || !ref.keySet().equals(Set.of("record_id", "revision")))
                    throw invalid(path + ".source_refs 每项须恰有 record_id 和 revision");
                String id = string(ref, "record_id", 64);
                long revision = integer(ref, "revision", 1);
                Long current = offered.get(id);
                boolean repeated = !seen.add(id);
                if (!id.matches("[0-9a-f]{64}") || repeated || !Objects.equals(current, revision))
                    throw invalid(path + ".source_refs 须引用本批不重复的当前来源，收到 " + id + "@" + revision
                            + (current == null ? "，本批未提供该 ID" : "，本批版本 " + current)
                            + (repeated ? "，该 ID 已引用" : ""));
            }
        }
        return changes;
    }

    private static long integer(JsonObject o, String k, long min) throws IOException {
        if (o.get(k) instanceof JsonPrimitive v && v.isNumber()) {
            try {
                long n = v.getAsBigDecimal().longValueExact();
                if (n >= min) return n;
            } catch (ArithmeticException e) {
                throw new IOException(k + " 须为不小于 " + min + " 的整数", e);
            }
        }
        throw invalid(k + " 须为不小于 " + min + " 的整数");
    }

    private static String string(JsonObject o, String k, int max) throws IOException {
        if (o != null && o.get(k) instanceof JsonPrimitive p && p.isString()) {
            var value = p.getAsString();
            if (!value.isBlank() && value.codePointCount(0, value.length()) <= max) return value;
        }
        throw invalid(k + " 须为不超过 " + max + " 个码点的非空字符串");
    }

    /** 反馈字段类型和数组长度即可定位数量错误，无需重新发送被拒绝的正文。 */
    private static String shape(JsonElement value) {
        if (value == null) return "缺失";
        if (value.isJsonNull()) return "null";
        if (value instanceof JsonArray array) return "数组（" + array.size() + " 项）";
        if (value.isJsonObject()) return "对象";
        var scalar = value.getAsJsonPrimitive();
        if (scalar.isString()) return "字符串";
        return scalar.isNumber() ? "数字" : "布尔值";
    }

    private static IOException invalid(String field) {
        return new IOException("Dreaming 模型违反契约 " + field);
    }

    @Override
    public void close() {
        client.close();
    }
}
