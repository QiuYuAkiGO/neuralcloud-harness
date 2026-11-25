package neuralcloud.controlplane.dreaming;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DreamingModelTest {
    private static final String MODEL = "dream-test";
    private static final String GEMINI = "/v1beta/models/" + MODEL + ":generateContent";
    private static final String OPENAI = "/v1/chat/completions";
    private static final String FUNCTION = "submit_dreaming";
    private static final String MESSAGE = "a3".repeat(32);
    private static final String RECEIPT = "b4".repeat(32);
    private static final String OLD_SOURCE = "c5".repeat(32);
    private static final Set<String> FIELDS = Set.of("kind", "subject", "expected_version", "text", "scope",
            "reason", "source_refs");

    /** 旧档案与本批第一手证据具有不同的来源；轮换模型时两者的用途相同。 */
    private static JsonObject batch() {
        return object("""
                {"stream_id":"memory-test","policy_version":1,"after":12,"through":14,
                 "evidence":[{"record_id":"%s","revision":2,"content":"青禾说周三读书。"},
                             {"record_id":"%s","revision":1,"content":"读书会已登记。"}],
                 "profiles":[{"kind":"topic","subject":"读书会","version":3,"text":"我记得以前安排在周五。",
                              "scope":"qq:group:41","reason":"此前记录","source_refs":[{"record_id":"%s","revision":1}]}]}
                """.formatted(MESSAGE, RECEIPT, OLD_SOURCE));
    }

    private static JsonObject person() {
        return object("""
                {"kind":"person","subject":"青禾","expected_version":0,"text":"我觉得青禾愿意领读。",
                 "scope":"qq:group:41","reason":"青禾在本轮主动提起读书。",
                 "source_refs":[{"record_id":"%s","revision":2}]}
                """.formatted(MESSAGE));
    }

    private static JsonObject topic() {
        return object("""
                {"kind":"topic","subject":"读书会","expected_version":3,"text":"我记下读书会改到周三。",
                 "scope":"qq:group:41","reason":"发言和登记回执一致。",
                 "source_refs":[{"record_id":"%s","revision":2},{"record_id":"%s","revision":1}]}
                """.formatted(MESSAGE, RECEIPT));
    }

    /** 经确认的青禾已建档。同一条原话还提到同名刊物和另一位署名相近的校对者。 */
    private static JsonObject namedBatch() {
        return object("""
                {"stream_id":"memory-test","policy_version":1,"after":12,"through":13,
                 "evidence":[{"record_id":"%s","revision":2,"content":"青穗说我来领读《青穗》，青穗子校对。"}],
                 "profiles":[{"kind":"person","subject":"青禾","version":4,"author":"DREAMING",
                              "text":"我记得青禾领读过。","scope":"读书会","reason":"领读时的发言",
                              "source_refs":[{"record_id":"%s","revision":2}]}],
                 "person_names":[{"subject":"青禾","aliases":["青穗"]}]}
                """.formatted(MESSAGE, MESSAGE));
    }

    private static JsonObject namedChange(String kind, String subject, int version, String text) {
        var value = person();
        value.addProperty("kind", kind);
        value.addProperty("subject", subject);
        value.addProperty("expected_version", version);
        value.addProperty("text", text);
        value.addProperty("scope", "读书会");
        value.addProperty("reason", "本次发言说明了刊名和分工。");
        return value;
    }

    /** 两种模型都收到同一份主称呼与别称映射；修订人物、建立刊物与另一人的身份可以共存。 */
    @Test public void sendsConfirmedNamesAndAcceptsThePrimaryPersonInBothProtocols() throws Exception {
        var packet = namedBatch();
        var expected = changes(
                namedChange("person", "青禾", 4, "我记得青禾会领读这本刊物。"),
                namedChange("topic", "青穗", 0, "我记下《青穗》是本次领读的刊物。"),
                namedChange("person", "青穗子", 0, "我记下青穗子负责校对。"));
        for (String path : List.of(GEMINI, OPENAI)) {
            try (var provider = new Provider()) {
                boolean nativeGemini = GEMINI.equals(path);
                var config = settings(nativeGemini ? "GEMINI" : "OPENAI_CHAT", provider.base() + path);
                if (nativeGemini) config.setProperty("apiKeyEnv", credentialEnv());
                provider.reply = nativeGemini ? gemini("STOP", expected.toString())
                        : openAi("tool_calls", tool(FUNCTION, expected.toString()));
                try (var model = DreamingModel.open(config)) {
                    assertEquals(expected.get("changes"), model.organize(packet));
                }
                var request = provider.onlyRequest(path).body();
                var messages = request.getAsJsonArray(nativeGemini ? "contents" : "messages");
                var user = messages.asList().stream().map(entry -> entry.getAsJsonObject())
                        .filter(message -> message.get("role").getAsString().equals("user"))
                        .findFirst().orElseThrow();
                String input = nativeGemini
                        ? user.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString()
                        : user.get("content").getAsString();
                assertEquals(path, packet.get("person_names"), object(input).get("person_names"));
            }
        }
    }

    /** 别称即使能新建一份第 1 版人物档案，也必须在模型边界报告错误字段。 */
    @Test public void rejectsAnAliasUsedAsAPersonSubject() throws Exception {
        var proposal = changes(namedChange("person", "青穗", 0, "我记下青穗会领读。"));
        try (var provider = new Provider();
             var model = DreamingModel.open(settings("OPENAI_CHAT", provider.base() + OPENAI))) {
            provider.reply = openAi("tool_calls", tool(FUNCTION, proposal.toString()));
            var failure = assertThrows(IOException.class, () -> model.organize(namedBatch()));
            assertTrue(failure.getMessage(), failure.getMessage().contains("subject"));
        }
    }

    /** Gemini 发送原生内容与结构约束，然后从正常结束的候选读取可见 JSON。 */
    @Test public void geminiReturnsNewAndRevisedProfilesFromNativeStructuredContent() throws Exception {
        var expected = changes(person(), topic());
        try (var provider = new Provider()) {
            var settings = settings("GEMINI", provider.base() + GEMINI);
            settings.setProperty("apiKeyEnv", credentialEnv());
            provider.reply = gemini("STOP", expected.toString());
            try (var model = DreamingModel.open(settings)) {
                assertEquals(expected.get("changes"), model.organize(batch()));
            }
            var sent = provider.onlyRequest(GEMINI);
            assertTrue(sent.googleCredential());
            var generation = sent.body().getAsJsonObject("generationConfig");
            assertEquals("application/json", generation.get("responseMimeType").getAsString());
            assertEquals(768, generation.get("maxOutputTokens").getAsInt());
            var schema = generation.has("responseJsonSchema") ? generation.getAsJsonObject("responseJsonSchema")
                    : generation.getAsJsonObject("responseSchema");
            assertChangesSchema(schema);
            assertMaterials(sent.body().getAsJsonArray("contents"));
        }
    }

    /** 同一批包含新人物和既有事项；修订正文以“我”开头时逐字保留。 */
    @Test public void acceptsFirstPersonRevisionVerbatim() throws Exception {
        try (var provider = new Provider()) {
            var settings = settings("GEMINI", provider.base() + GEMINI);
            settings.setProperty("apiKeyEnv", credentialEnv());
            var revised = topic();
            revised.addProperty("text", "我暂时记下，读书会已经改到了周三。");
            var expected = changes(person(), revised);
            provider.reply = gemini("STOP", expected.toString());
            try (var model = DreamingModel.open(settings)) {
                assertEquals(expected.get("changes"), model.organize(batch()));
            }
        }
    }

    /** 即使新人物正文合规，第三人称修订或“我”前有空格仍使整个结果失效。 */
    @Test public void rejectsWholeBatchWhenRevisionDoesNotStartWithFirstPerson() throws Exception {
        try (var provider = new Provider()) {
            var settings = settings("GEMINI", provider.base() + GEMINI);
            settings.setProperty("apiKeyEnv", credentialEnv());
            try (var model = DreamingModel.open(settings)) {
                for (String text : List.of("读书会已经改到了周三。", " 我暂时记下，读书会已经改到了周三。")) {
                    var revised = topic();
                    revised.addProperty("text", text);
                    var proposal = changes(person(), revised);
                    reject(model, provider, gemini("STOP", proposal.toString()), text);
                }
            }
        }
    }

    /** 本地兼容端点可以免鉴权；强制选择的唯一函数允许报告已经看过、没有变化。 */
    @Test public void localOpenAiRequiresTheSingleToolAndAcceptsAnEmptyChangeSet() throws Exception {
        try (var provider = new Provider()) {
            var settings = settings("OPENAI_CHAT", provider.base() + OPENAI);
            settings.setProperty("batchSize", "16");
            provider.reply = openAi("tool_calls", tool(FUNCTION, changes().toString()));
            try (var model = DreamingModel.open(settings)) {
                assertEquals(new JsonArray(), model.organize(batch()));
            }
            var sent = provider.onlyRequest(OPENAI);
            assertFalse(sent.authorizationPresent());
            var body = sent.body();
            assertEquals(MODEL, body.get("model").getAsString());
            assertEquals(768, (body.has("max_completion_tokens") ? body.get("max_completion_tokens")
                    : body.get("max_tokens")).getAsInt());
            var tools = body.getAsJsonArray("tools");
            assertEquals(1, tools.size());
            var declared = tools.get(0).getAsJsonObject();
            assertEquals("function", declared.get("type").getAsString());
            var function = declared.getAsJsonObject("function");
            assertEquals(FUNCTION, function.get("name").getAsString());
            assertChangesSchema(function.getAsJsonObject("parameters"));
            var choice = body.get("tool_choice");
            if (choice.isJsonObject()) {
                assertEquals("function", choice.getAsJsonObject().get("type").getAsString());
                assertEquals(FUNCTION, choice.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
            } else {
                assertEquals("required", choice.getAsString());
            }
            assertMaterials(body.getAsJsonArray("messages"));
        }
    }

    /** 显式指定的代理和凭据走兼容协议；代理与目标地址都在回环网段。 */
    @Test public void openAiUsesTheConfiguredProxyAndBearerCredential() throws Exception {
        try (var proxy = new Provider()) {
            var settings = settings("OPENAI_CHAT", "http://127.0.0.2:" + proxy.port() + OPENAI);
            settings.setProperty("proxy", "127.0.0.1:" + proxy.port());
            settings.setProperty("apiKeyEnv", credentialEnv());
            settings.setProperty("batchSize", "1");
            var oneObservation = batch();
            oneObservation.getAsJsonArray("evidence").remove(1);
            oneObservation.addProperty("through", 13);
            var expected = changes(person());
            proxy.reply = openAi("tool_calls", tool(FUNCTION, expected.toString()));
            try (var model = DreamingModel.open(settings)) {
                assertEquals(expected.get("changes"), model.organize(oneObservation));
            }
            var sent = proxy.onlyRequest(OPENAI);
            assertTrue(sent.proxyRequest());
            assertTrue(sent.bearerCredential());
        }
    }

    /** 配置错误在正式工厂拒绝，可由部署方修改这一份模型配置。 */
    @Test public void openRejectsMissingOrOutOfRangeSettingsAndUnavailableCredentials() {
        var local = settings("OPENAI_CHAT", "http://127.0.0.1:1" + OPENAI);
        for (String field : local.stringPropertyNames()) {
            var missing = (Properties) local.clone();
            missing.remove(field);
            assertBadSettings(field, missing);
        }
        for (var pair : List.of(List.of("protocol", "AUTO"), List.of("endpoint", "relative/path"),
                List.of("model", " "), List.of("batchSize", "0"), List.of("batchSize", "17"),
                List.of("intervalSeconds", "0"), List.of("intervalSeconds", "1.5"),
                List.of("connectTimeoutSeconds", "0"), List.of("requestTimeoutSeconds", "-1"),
                List.of("maxOutputTokens", "0"), List.of("proxy", "127.0.0.1:65536"),
                List.of("apiKeyEnv", "NEURALCLOUD_TEST_ABSENT_8F391735"))) {
            var invalid = (Properties) local.clone();
            invalid.setProperty(pair.get(0), pair.get(1));
            assertBadSettings(pair.get(0), invalid);
        }
        assertBadSettings("apiKeyEnv", settings("GEMINI", "http://127.0.0.1:1" + GEMINI));
    }

    private static void assertBadSettings(String field, Properties settings) {
        assertThrows(field, IllegalArgumentException.class, () -> {
            try (var ignored = DreamingModel.open(settings)) { }
        });
    }

    /** 服务端出错、候选截断或返回另一种协议时，均不能视为完成了整理。 */
    @Test public void rejectsHttpErrorsTruncationAndWrongProtocolEnvelopes() throws Exception {
        String args = changes().toString();
        var single = tool(FUNCTION, args);
        try (var provider = new Provider()) {
            var settings = settings("GEMINI", provider.base() + GEMINI);
            settings.setProperty("apiKeyEnv", credentialEnv());
            try (var model = DreamingModel.open(settings)) {
                reject(model, provider, gemini("MAX_TOKENS", args), "Gemini 截断");
                reject(model, provider, gemini("STOP", "这是一段正文"), "Gemini 候选没有 JSON");
                reject(model, provider, openAi("tool_calls", single), "Gemini 收到兼容协议响应");
            }
            try (var model = DreamingModel.open(settings("OPENAI_CHAT", provider.base() + OPENAI))) {
                reject(model, provider, gemini("STOP", args), "兼容模型收到 Gemini 响应");
                reject(model, provider, openAi("length", single), "兼容模型截断");
                reject(model, provider, openAi("tool_calls", tool("write_profile", args)), "返回另一函数");
                reject(model, provider, openAi("tool_calls", single, single), "返回两个函数调用");
                reject(model, provider, openAi("tool_calls", tool(FUNCTION, "{")), "函数参数不完整");
                reject(model, provider, """
                        {"choices":[{"finish_reason":"stop","message":{"content":%s}}]}
                        """.formatted(new JsonPrimitive(args)), "用正文代替函数调用");
                provider.status = 403;
                reject(model, provider, openAi("tool_calls", single), "上游拒绝请求");
            }
        }
    }

    /** 即使前一项可以写入，后面任何字段或依据失约都应拒绝整组模型建议。 */
    @Test public void rejectsInvalidChangeFieldsDuplicatesAndReferencesOutsideThisEvidence() throws Exception {
        try (var provider = new Provider();
             var model = DreamingModel.open(settings("OPENAI_CHAT", provider.base() + OPENAI))) {
            for (String field : FIELDS) {
                var invalid = topic();
                invalid.remove(field);
                rejectChange(model, provider, invalid, "缺字段 " + field);
            }
            for (String patch : List.of("{\"kind\":\"place\"}", "{\"subject\":false}", "{\"text\":\"\"}",
                    "{\"scope\":null}", "{\"reason\":42}", "{\"expected_version\":-1}",
                    "{\"expected_version\":1.5}", "{\"expected_version\":\"3\"}",
                    "{\"author\":\"MASTER\"}", "{\"source_refs\":[]}")) {
                var invalid = topic();
                object(patch).entrySet().forEach(e -> invalid.add(e.getKey(), e.getValue()));
                rejectChange(model, provider, invalid, patch);
            }
            for (String patch : List.of("{\"record_id\":\"" + OLD_SOURCE + "\",\"revision\":1}", "{\"revision\":1}",
                    "{\"record_id\":\"" + MESSAGE.toUpperCase(Locale.ROOT) + "\"}", "{\"revision\":0}",
                    "{\"revision\":\"2\"}", "{\"extra\":true}")) {
                var invalid = topic();
                var ref = invalid.getAsJsonArray("source_refs").get(0).getAsJsonObject();
                object(patch).entrySet().forEach(e -> ref.add(e.getKey(), e.getValue()));
                rejectChange(model, provider, invalid, "无效来源 " + patch);
            }
            var repeated = topic();
            var refs = repeated.getAsJsonArray("source_refs");
            refs.add(refs.get(0).deepCopy());
            rejectChange(model, provider, repeated, "重复来源");
            rejectChange(model, provider, person(), "同批重复的档案身份");
            var extra = changes(person());
            extra.addProperty("author", "MODEL");
            for (String invalid : List.of(extra.toString(), "{}", "{\"changes\":{}}")) {
                reject(model, provider, openAi("tool_calls", tool(FUNCTION, invalid)), "输出包装失约");
            }
        }
    }

    private static void rejectChange(DreamingModel model, Provider provider, JsonObject invalid, String reason) {
        reject(model, provider, openAi("tool_calls", tool(FUNCTION, changes(person(), invalid).toString())), reason);
    }

    private static void reject(DreamingModel model, Provider provider, String reply, String reason) {
        provider.reply = reply;
        int before = provider.requests.size();
        assertThrows(reason, IOException.class, () -> model.organize(batch()));
        assertEquals(reason, before + 1, provider.requests.size());
    }

    /** 两种协议都须交付原始材料，且在模型端声明变化及其来源的结构。 */
    private static void assertMaterials(JsonArray contents) {
        String input = contents.toString();
        for (String marker : List.of(MESSAGE, RECEIPT, "青禾说周三读书。", "我记得以前安排在周五。"))
            assertTrue("模型输入缺少 " + marker, input.contains(marker));
    }

    private static void assertChangesSchema(JsonObject schema) {
        var item = schema.getAsJsonObject("properties").getAsJsonObject("changes").getAsJsonObject("items");
        assertEquals(FIELDS, item.getAsJsonObject("properties").keySet());
        var ref = item.getAsJsonObject("properties").getAsJsonObject("source_refs").getAsJsonObject("items");
        assertEquals(Set.of("record_id", "revision"), ref.getAsJsonObject("properties").keySet());
    }

    private static Properties settings(String protocol, String endpoint) {
        var p = new Properties();
        p.setProperty("protocol", protocol);
        p.setProperty("endpoint", endpoint);
        p.setProperty("model", MODEL);
        p.setProperty("connectTimeoutSeconds", "2");
        p.setProperty("requestTimeoutSeconds", "5");
        p.setProperty("maxOutputTokens", "768");
        p.setProperty("batchSize", "2");
        p.setProperty("intervalSeconds", "1");
        return p;
    }

    private static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static JsonObject changes(JsonObject... entries) {
        var array = new JsonArray();
        for (var entry : entries) array.add(entry);
        var result = new JsonObject();
        result.add("changes", array);
        return result;
    }

    private static String gemini(String finish, String content) {
        return """
                {"candidates":[{"finishReason":"%s","content":{"role":"model","parts":[{"text":%s}]}}]}
                """.formatted(finish, new JsonPrimitive(content));
    }

    private static JsonObject tool(String name, String arguments) {
        return object("""
                {"type":"function","id":"test-call","function":{"name":"%s","arguments":%s}}
                """.formatted(name, new JsonPrimitive(arguments)));
    }

    private static String openAi(String finish, JsonObject... calls) {
        var array = new JsonArray();
        for (var call : calls) array.add(call);
        return """
                {"choices":[{"finish_reason":"%s","message":{"role":"assistant","content":null,"tool_calls":%s}}]}
                """.formatted(finish, array);
    }

    /** 使用平台已有的普通环境值验证凭据来源；只记录请求头是否匹配。 */
    private static String credentialEnv() {
        for (String key : List.of("OS", "LANG", "LC_ALL", "TERM", "SHLVL")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) return key;
        }
        throw new AssertionError("测试进程需要非空的 OS、LANG、LC_ALL、TERM 或 SHLVL");
    }

    private record Request(String method, String path, boolean hasQuery, boolean proxyRequest,
                           boolean authorizationPresent, boolean bearerCredential, boolean googleCredential,
                           JsonObject body) { }

    /** 回环 HTTP 服务只在测试指定的状态和正文下响应，观察器记录协议边界。 */
    private static class Provider implements AutoCloseable {
        final HttpServer server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String reply = "";

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var target = exchange.getRequestURI();
                    String key = System.getenv(credentialEnv());
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    var body = object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    requests.add(new Request(exchange.getRequestMethod(), target.getPath(), target.getRawQuery() != null,
                            target.isAbsolute(), authorization != null, ("Bearer " + key).equals(authorization),
                            key.equals(exchange.getRequestHeaders().getFirst("x-goog-api-key")), body));
                    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        int port() { return server.getAddress().getPort(); }
        String base() { return "http://127.0.0.1:" + port(); }

        Request onlyRequest(String path) {
            assertEquals(1, requests.size());
            var sent = requests.getFirst();
            assertEquals("POST", sent.method());
            assertEquals(path, sent.path());
            assertFalse(sent.hasQuery());
            return sent;
        }

        @Override public void close() { server.stop(0); }
    }
}
