package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.app.ChatCompactionFixture.Call;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** 核对模型实际收到的规划指引、可提交的类别，以及首次压缩后的续聊和正式查证。 */
@RunWith(Parameterized.class)
public class ChatCompactionPlanningTest {
    @Parameterized.Parameters(name = "{0}") public static List<ApiFormat> formats() {
        return List.of(ApiFormat.OPENAI_CHAT, ApiFormat.GEMINI);
    }
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final String CHAIN = "plan";
    private static final String QUESTION = "那两个候选地点分别是哪儿？";
    private static final String NOTE = "用户正在灯塔丘和石竹湾之间选择，尚未决定。";
    private static final String HISTORY = "{\"kind\":\"HISTORY\",\"query\":\"春游 集合时间\"}";
    private static final String EVIDENCE = "春游集合时间是六点一刻。";
    private static final String CLARIFICATION = "你说的是哪两个地点？给我一点线索好吗？";
    private final ApiFormat format;
    public ChatCompactionPlanningTest(ApiFormat format) { this.format = format; }

    /** 正例只需提要中的指代。无 Memory 时模型能提交 REPLY，用户实际收到两处地名。 */
    @Test public void summarySupportsAReplyWithoutAdvertisingUnavailableHistoryLookup() throws Exception {
        try (var f = seeded()) {
            f.reply = call -> saw(call, NOTE) ? "灯塔丘和石竹湾。" : "我不清楚。";
            assertEquals("灯塔丘和石竹湾。", f.turn(CHAIN, QUESTION));
            var plan = f.calls("plan_turn").getFirst();
            assertTrue(saw(plan, NOTE));
            assertTrue(kinds(plan).contains("REPLY"));
            assertFalse(kinds(plan).contains("HISTORY"));
            assertFalse("规划系统仍将不可用的 HISTORY 列作可选操作", recommendsHistory(plan));
        }
    }

    /** 同样有有效提要，模型仍擅自提交 HISTORY 时须沿原错误契约返回，不能生成或登记答复。 */
    @Test public void submittingHistoryOutsideTheSchemaFailsBeforeDraftOrDelivery() throws Exception {
        try (var f = seeded()) {
            f.plan = call -> HISTORY;
            assertThrows(IOException.class, () -> f.turn(CHAIN, QUESTION));
            var plan = f.calls("plan_turn").getFirst();
            assertTrue(saw(plan, NOTE));
            assertFalse(kinds(plan).contains("HISTORY"));
            assertTrue(f.calls("reply").isEmpty());
            assertTrue(f.audit.events(CHAIN).stream().noneMatch(e -> e.type().equals("console_response_written")));
        }
    }

    /** 无查询能力时，规划请求须把 query 声明为必填且只可为空。合法的澄清选择应实际交付。 */
    @Test public void aClarificationWithAnEmptyQueryIsDeclaredAndDelivered() throws Exception {
        try (var f = new ChatCompactionFixture(folder.newFolder().toPath(), format, "contextCompression=OFF\n")) {
            f.plan = call -> "{\"kind\":\"CLARIFY\",\"query\":\"\"}";
            f.reply = call -> CLARIFICATION;
            assertEquals(CLARIFICATION, f.turn(CHAIN, QUESTION));
            var plan = f.calls("plan_turn").getFirst();
            assertTrue(kinds(plan).contains("CLARIFY"));
            assertFalse(kinds(plan).contains("HISTORY"));
            var schema = planSchema(plan);
            assertTrue(schema.getAsJsonArray("required").contains(new JsonPrimitive("query")));
            var query = schema.getAsJsonObject("properties").getAsJsonObject("query");
            assertTrue("无查询权限时 query 必须只允许空串，实收 " + query, emptyStringOnly(query));
        }
    }

    /** 将澄清理由塞进 query 是无效规划。正式适配器在生成和渠道确认前拒绝它。 */
    @Test public void aClarificationWithItsExplanationInQueryIsRejected() throws Exception {
        try (var f = new ChatCompactionFixture(folder.newFolder().toPath(), format, "contextCompression=OFF\n")) {
            f.plan = call -> "{\"kind\":\"CLARIFY\",\"query\":\"需要请对方先说明是哪两个候选地点\"}";
            assertThrows(IOException.class, () -> f.turn(CHAIN, QUESTION));
            assertTrue(f.calls("reply").isEmpty());
            assertTrue(f.audit.events(CHAIN).stream().noneMatch(e -> e.type().equals("console_response_written")));
        }
    }

    /** 当本轮有 HISTORY 权限，原文查证仍实际到达 Memory；证据独有的时刻须进入答复模型。 */
    @Test public void enabledHistoryStillLooksUpTheOriginalThroughMemory() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seq = new AtomicInteger();
        var lookup = new AtomicReference<String>();
        server.createContext("/v1/ingest", exchange -> {
            int n = seq.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, Map.of("status", "DURABLE", "record_id", "%064x".formatted(n), "revision", 1, "seq", n));
        });
        server.createContext("/v1/recall", exchange -> {
            var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            lookup.set(request.get("query").getAsString());
            var source = Map.of("kind", "observation", "privacy", "private", "character_id", "misty", "source_instance", "neuralcloud-java",
                    "session_id", "console:" + CHAIN, "conversation_id", "console:" + CHAIN);
            respond(exchange, Map.of("status", "OK", "items", List.of(Map.of("status", "ONLINE", "record_id", "a".repeat(64),
                    "revision", 1, "kind", "observation", "content", EVIDENCE, "occurred_at", 5, "source", source))));
        });
        server.start();
        try (var f = seeded(); var client = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var resident = new ResidentMemory(client, endpoint, "misty", "test-token", true);
            f.memory = Optional.of(new ConsoleMain.MemoryAccess(new ConversationMemory(f.audit, resident),
                    new RouterMemoryWriter(f.audit, resident), Optional.empty()));
            f.plan = call -> HISTORY;
            f.reply = call -> saw(call, EVIDENCE) ? "六点一刻。" : "我还没有查到。";
            assertEquals("六点一刻。", f.turn(CHAIN, "请核对春游集合时间的原始记录。"));
            var plan = f.calls("plan_turn").getFirst();
            assertTrue(saw(plan, NOTE));
            assertTrue(kinds(plan).contains("HISTORY"));
            var query = planSchema(plan).getAsJsonObject("properties").getAsJsonObject("query");
            assertTrue("有 HISTORY 权限时 query 应允许实际检索线索 " + query, allowsText(query, "春游 集合时间"));
            assertEquals("春游 集合时间", lookup.get());
        } finally {
            server.stop(0);
        }
    }

    private ChatCompactionFixture seeded() throws IOException {
        var f = new ChatCompactionFixture(folder.newFolder().toPath(), format, "contextCompression=ROLLING\nhistoryBudgetUnits=4096\n");
        f.inbound(CHAIN, "春游我想在灯塔丘和石竹湾之间选，还拿不准。" + "远".repeat(1_900));
        f.inbound(CHAIN, "我又整理了一本画册。" + "近".repeat(1_500));
        f.summary = call -> JSON.toJson(Map.of("items", List.of(Map.of("kind", "OPEN_POINT", "text", NOTE, "sources", List.of(1))),
                "closed", List.of()));
        return f;
    }

    private static boolean saw(Call call, String text) {
        return call.messages().stream().anyMatch(m -> !m.role().equals("system") && m.text().contains(text));
    }

    private static List<String> kinds(Call call) {
        return planSchema(call).getAsJsonObject("properties").getAsJsonObject("kind").getAsJsonArray("enum").asList()
                .stream().map(value -> value.getAsString()).toList();
    }

    private static JsonObject planSchema(Call call) {
        var body = call.body();
        if (body.has("tools")) return body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters");
        var generation = body.getAsJsonObject("generationConfig");
        return generation.getAsJsonObject(generation.has("responseSchema") ? "responseSchema" : "responseJsonSchema");
    }

    /** 两端均可用单值列表声明。允许表达相同字符串约束的 const 或零长度上限。 */
    private static boolean emptyStringOnly(JsonObject query) {
        var empty = new JsonPrimitive("");
        return allowsText(query, "") && (empty.equals(query.get("const"))
                || (query.has("enum") && query.getAsJsonArray("enum").asList().equals(List.of(empty)))
                || (query.has("type") && query.has("maxLength") && query.get("maxLength").getAsInt() == 0));
    }

    private static boolean allowsText(JsonObject query, String text) {
        var value = new JsonPrimitive(text);
        int length = text.codePointCount(0, text.length());
        return (!query.has("type") || "string".equalsIgnoreCase(query.get("type").getAsString()))
                && (!query.has("const") || value.equals(query.get("const")))
                && (!query.has("enum") || query.getAsJsonArray("enum").contains(value))
                && (!query.has("minLength") || query.get("minLength").getAsInt() <= length)
                && (!query.has("maxLength") || query.get("maxLength").getAsInt() >= length);
    }

    /** 比较协议类别名的肯定指引。允许系统明确告知某项不可用，不要求新提示采用固定句式。 */
    private static boolean recommendsHistory(Call plan) {
        var offered = Pattern.compile("(?:^\\s*HISTORY\\s*[:：]|(?:选(?:择)?|调用|使用|提交)\\s*HISTORY(?![A-Z_]))");
        var denied = Pattern.compile("不可用|未开放|未提供|不(?:得|要|能|可|应|支持|允许)|禁止");
        return Arrays.stream(plan.messages().getFirst().text().split("[。；;\n]"))
                .anyMatch(sentence -> offered.matcher(sentence).find() && !denied.matcher(sentence).find());
    }

    private static void respond(HttpExchange exchange, Object value) throws IOException {
        try (exchange) {
            byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
