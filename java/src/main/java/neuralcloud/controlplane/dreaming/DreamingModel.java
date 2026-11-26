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
    private static final Set<String> CHANGE_FIELDS =
            Set.of("kind", "subject", "expected_version", "text", "scope", "reason", "source_refs");
    private static final Set<String> CLAIM_FIELDS =
            Set.of("person_id", "expected_identity_version", "name", "relation", "scope", "source_refs");
    private static final Set<String> RELATIONS =
            Set.of("SELF_NAME", "PREFERRED_NAME", "DIRECT_CONFIRMATION", "MENTION");
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

    /** 旧调用方只消费认识修订；单独返回称呼的批次需要走完整提案入口。 */
    public JsonArray organize(JsonObject batch) throws IOException, InterruptedException {
        var proposal = propose(batch, null);
        if (!proposal.nameClaims().isEmpty()) throw new IOException("称呼提案需随认识一并提交");
        return proposal.changes();
    }

    /** 两类操作归同一领取位置，宿主在一个带持久事件键的命令中交给 Memory。 */
    record Proposal(JsonArray changes, JsonArray nameClaims) {}

    /** 只向两个后端交付本批获准的原句、档案和人物提示，水位与提交权限留在宿主。 */
    Proposal propose(JsonObject batch, String correction) throws IOException, InterruptedException {
        var material = new JsonObject();
        material.add("evidence", batch.get("evidence"));
        material.add("profiles", batch.get("profiles"));
        var aliases = new HashMap<String, String>();
        if (batch.has("person_names")) {
            // 属于本批次的已确认称呼同时约束提示词和模型输出，旧版 Memory 可省略此字段。
            if (!(batch.get("person_names") instanceof JsonArray names)) throw invalid("person_names");
            for (var raw : names) {
                if (!(raw instanceof JsonObject person)
                        || !(person.get("aliases") instanceof JsonArray previous))
                    throw invalid("person_names.aliases");
                String subject = string(person, "subject", 128);
                for (var name : previous) {
                    if (!(name instanceof JsonPrimitive p) || !p.isString())
                        throw invalid("person_names.aliases");
                    aliases.put(p.getAsString(), subject);
                }
            }
            material.add("person_names", names);
        }
        var people = new HashMap<String, JsonObject>();
        if (batch.has("persons")) {
            if (!(batch.get("persons") instanceof JsonArray persons)) throw invalid("persons");
            for (var raw : persons) {
                if (!(raw instanceof JsonObject person)
                        || !(person.get("aliases") instanceof JsonArray)
                        || people.putIfAbsent(string(person, "person_id", 128), person) != null)
                    throw invalid("persons.person_id/aliases");
                integer(person, "identity_version", 0);
                if (person.has("subject")) string(person, "subject", 128);
            }
            material.add("persons", persons);
        }
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
            return checked(JSON.fromJson(arguments, JsonObject.class), batch, people, aliases);
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

    /** 核对两个操作数组及其各自的版本。未收到任何人物引用的旧批仍接受仅含 changes 的响应。 */
    private static Proposal checked(JsonObject output, JsonObject batch, Map<String, JsonObject> people,
                                    Map<String, String> aliases) throws IOException {
        if (output == null || !(output.get("changes") instanceof JsonArray changes) || changes.size() > 8)
            throw invalid("changes 须为 0–8 项的数组，实际为 " + shape(output == null ? null : output.get("changes")));
        JsonArray claims;
        if (people.isEmpty() && output.keySet().equals(Set.of("changes"))) {
            claims = new JsonArray();
        } else if (output.keySet().equals(Set.of("changes", "name_claims"))
                && output.get("name_claims") instanceof JsonArray array && array.size() <= 16) {
            claims = array;
        } else {
            throw invalid("有签发人物时须提交 changes 与 name_claims，称呼提案须为 0–16 项的数组，实际字段 " + output.keySet());
        }
        var offered = new HashMap<String, JsonObject>();
        for (var entry : batch.getAsJsonArray("evidence")) {
            var evidence = entry.getAsJsonObject();
            offered.put(evidence.get("record_id").getAsString(), evidence);
        }
        var proposedNames = checkClaims(claims, people, offered);
        checkChanges(changes, batch.getAsJsonArray("profiles"), people, aliases, proposedNames, offered);
        return new Proposal(changes, claims);
    }

    /** 只有本批签发的身份版本和逐字原话可用于称呼；旧主名和同批可能生效的新主名交给 Memory 裁定。 */
    private static Set<List<String>> checkClaims(JsonArray claims, Map<String, JsonObject> people,
                                                 Map<String, JsonObject> offered) throws IOException {
        var proposedNames = new HashSet<List<String>>();
        for (int index = 0; index < claims.size(); index++) {
            var entry = claims.get(index);
            String path = "name_claims[" + index + "]";
            if (!(entry instanceof JsonObject claim) || !claim.keySet().equals(CLAIM_FIELDS))
                throw invalid(path + " 须恰含 " + CLAIM_FIELDS);
            String id = string(claim, "person_id", 128);
            var person = people.get(id);
            if (person == null) throw invalid(path + ".person_id 未在本批签发，收到 " + id);
            long version = person.get("identity_version").getAsLong();
            if (integer(claim, "expected_identity_version", 0) != version)
                throw invalid(path + ".expected_identity_version 应为 " + version);
            String name = string(claim, "name", 128), relation = string(claim, "relation", 32);
            if (!RELATIONS.contains(relation)) throw invalid(path + ".relation 须为 " + RELATIONS);
            String scope = string(claim, "scope", 256);
            var sources = sources(path, claim, offered, true);
            if (!scope.equals("global") && sources.stream().noneMatch(
                    e -> e.has("conversation_id") && e.get("conversation_id").getAsString().equals(scope)))
                throw invalid(path + ".scope 须为 global 或引用原句所在会话");
            if (!relation.equals("MENTION") && (!person.has("subject")
                    || (relation.equals("PREFERRED_NAME") && scope.equals("global"))))
                proposedNames.add(List.of(id, name));
        }
        return proposedNames;
    }

    /** QQ 按签发 ID 和人物版本定位，同名的两份认识可独立修订。无身份材料的旧档沿用名称。 */
    private static void checkChanges(JsonArray changes, JsonArray profiles, Map<String, JsonObject> people,
                                     Map<String, String> aliases, Set<List<String>> proposedNames,
                                     Map<String, JsonObject> offered) throws IOException {
        var byId = new HashMap<String, JsonObject>();
        var byName = new HashMap<List<String>, JsonObject>();
        var ambiguous = new HashSet<List<String>>();
        for (var entry : profiles) {
            var profile = entry.getAsJsonObject();
            if (profile.has("person_id")) byId.put(profile.get("person_id").getAsString(), profile);
            var key = List.of(profile.get("kind").getAsString(), profile.get("subject").getAsString());
            if (byName.putIfAbsent(key, profile) != null) ambiguous.add(key);
        }
        var identities = new HashSet<List<String>>();
        for (int index = 0; index < changes.size(); index++) {
            var entry = changes.get(index);
            String path = "changes[" + index + "]";
            if (!(entry instanceof JsonObject item) || !item.keySet().containsAll(CHANGE_FIELDS)
                    || item.size() != CHANGE_FIELDS.size() + (item.has("person_id") ? 1 : 0))
                throw invalid(path + " 须包含 " + CHANGE_FIELDS + "，人物可附 person_id");
            String kind = string(item, "kind", 16);
            String subject = string(item, "subject", 128);
            if (!Set.of("person", "topic").contains(kind)) throw invalid(path + ".kind 须为 person 或 topic");
            var sources = sources(path, item, offered, false);
            var identity = List.of(kind, subject);
            JsonObject prior;
            if (item.has("person_id")) {
                String id = string(item, "person_id", 128);
                var person = people.get(id);
                if (!kind.equals("person") || person == null) throw invalid(path + ".person_id 须填写本批签发的目标人物 ID");
                if (!(person.has("subject") && person.get("subject").getAsString().equals(subject))
                        && !proposedNames.contains(List.of(id, subject))
                        && (person.has("subject") || !initialName(id, subject, offered.values())))
                    throw invalid(path + ".subject 须为此人的有效主称呼或同批有据的新称呼");
                identity = List.of(kind, "person_id", id);
                prior = byId.get(id);
            } else {
                if (ambiguous.contains(identity) || (kind.equals("person")
                        && sources.stream().anyMatch(e -> e.has("speaker_person_id"))))
                    throw invalid(path + ".person_id 须填写本批签发的目标人物 ID");
                if (kind.equals("person") && aliases.containsKey(subject))
                    throw invalid("subject 须使用主称呼 " + aliases.get(subject));
                prior = byName.get(identity);
                if (prior != null && prior.has("person_id"))
                    identity = List.of(kind, "person_id", prior.get("person_id").getAsString());
            }
            if (!identities.add(identity)) throw invalid(path + " 重复修订同一人物或主题");
            long expected = prior == null ? 0L : prior.get("version").getAsLong();
            long actual = integer(item, "expected_version", 0);
            if (actual != expected)
                throw invalid(path + ".expected_version 应为 " + expected + "，实际 " + actual);
            if (!string(item, "text", 2048).startsWith("我")) throw invalid("text 须以我开头");
            string(item, "scope", 512);
            string(item, "reason", 1024);
        }
    }

    /** Memory 在本次提交内登记真实渠道称呼。首批尚无主名时，模型可引用该人的已签发显示名。 */
    private static boolean initialName(String id, String name, Collection<JsonObject> evidence) {
        var owner = new JsonPrimitive(id);
        var label = new JsonPrimitive(name);
        for (var source : evidence) {
            if (owner.equals(source.get("speaker_person_id"))
                    && (label.equals(source.get("nickname")) || label.equals(source.get("card")))) return true;
            if (owner.equals(source.get("reviewed_person_id")) && label.equals(source.get("reviewed_name"))) return true;
        }
        return false;
    }

    /** 同项引用按记录去重，称呼还需逐字匹配该版获准原文；判断谁在说谁由权威端复核。 */
    private static List<JsonObject> sources(String path, JsonObject item, Map<String, JsonObject> offered, boolean quoted)
            throws IOException {
        if (!(item.get("source_refs") instanceof JsonArray refs) || refs.isEmpty() || refs.size() > 16)
            throw invalid(path + ".source_refs 须为含 1–16 条来源的数组，实际为 " + shape(item.get("source_refs")));
        var expected = quoted ? Set.of("record_id", "revision", "quote") : Set.of("record_id", "revision");
        var seen = new HashSet<String>();
        var sources = new ArrayList<JsonObject>();
        for (var raw : refs) {
            if (!(raw instanceof JsonObject ref) || !ref.keySet().equals(expected)) throw invalid(path + ".source_refs 每项须恰含 " + expected);
            String id = string(ref, "record_id", 64);
            var source = offered.get(id);
            long revision = integer(ref, "revision", 1);
            boolean repeated = !seen.add(id);
            if (repeated || source == null || source.get("revision").getAsLong() != revision)
                throw invalid(path + ".source_refs 须引用本批不重复的当前来源，收到 " + id + "@" + revision
                        + (source == null ? "，本批未提供该 ID" : "，本批版本 " + source.get("revision"))
                        + (repeated ? "，该 ID 已引用" : ""));
            if (quoted && !source.get("content").getAsString().contains(string(ref, "quote", 2048)))
                throw invalid(path + ".source_refs.quote 须逐字出自 " + id + "@" + revision + " 的原话");
            sources.add(source);
        }
        return sources;
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
