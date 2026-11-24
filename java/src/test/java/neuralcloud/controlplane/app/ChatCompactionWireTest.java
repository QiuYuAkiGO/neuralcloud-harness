package neuralcloud.controlplane.app;

import com.google.gson.JsonObject;
import neuralcloud.controlplane.app.ChatCompactionFixture.Call;
import neuralcloud.controlplane.app.ChatCompactionFixture.Message;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 以真实配置、网页入站及供应商 HTTP 请求验收水位、兼容性、协议消息位置和窗口错误。 */
public class ChatCompactionWireTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String ROLLING = "contextCompression=ROLLING\n";
    private static final String QUESTION = "上次选的路怎么走？请保留🧭和这两个空格  。";
    private static final String TOPIC = "上次提到的路线会经过北岸小径。";
    private static final String CANDIDATE = """
            {"items":[{"kind":"TOPIC","text":"上次提到的路线会经过北岸小径。","sources":[1]}],"closed":[]}
            """;

    /** 正好 36,000 单元时冻结旧前缀。规划、答复读取同一快照，最近完整原文占用 20,000 单元。 */
    @Test public void freezesAtTheInclusiveWatermarkAndReusesOneProjection() throws Exception {
        try (var f = fixture(ApiFormat.OPENAI_CHAT, ROLLING)) {
            var raw = fill(f, 16_000);
            f.summary = call -> CANDIDATE;
            f.turn("walk", QUESTION);
            assertEquals(List.of("submit_chat_summary", "plan_turn", "reply"),
                    f.calls.stream().map(Call::name).filter(name -> !"select_route".equals(name)).toList());
            var summary = f.calls("submit_chat_summary").getFirst();
            var entries = summary.entries();
            assertEquals(1, entries.size());
            assertEquals(1, entries.get(0).getAsJsonObject().get("index").getAsInt());
            assertEquals(raw.getFirst(), entries.get(0).getAsJsonObject().get("text").getAsString());
            assertFalse(summary.body().toString().contains(QUESTION));
            var planning = history(f.calls("plan_turn").getFirst());
            assertEquals(planning, history(f.calls("reply").getFirst()));
            assertEquals(3, planning.size());
            assertTrue(planning.getFirst().text().contains(TOPIC));
            assertEquals(raw.subList(1, 3), planning.subList(1, 3).stream().map(Message::text).toList());
            assertTrue(planning.stream().mapToInt(m -> m.text().length()).sum() <= 24_000);
        }
    }

    /** 减去一个 UTF-16 单元。当前问题跨过水位也须保持原文，旧历史照常送入规划。 */
    @Test public void oneUnitBelowTheWatermarkExcludesTheCurrentQuestionFromTheCount() throws Exception {
        try (var f = fixture(ApiFormat.OPENAI_CHAT, ROLLING)) {
            var raw = fill(f, 15_999);
            f.turn("walk", QUESTION);
            assertTrue(f.calls("submit_chat_summary").isEmpty());
            var plan = f.calls("plan_turn").getFirst();
            assertEquals(QUESTION, plan.messages().getLast().text());
            assertEquals(raw, history(plan).stream().map(Message::text).toList());
        }
    }

    /** 缺省和显式 OFF 在越过水位后仍以原文规划。每种配置启动自己的模型档位和审计目录。 */
    @Test public void existingAndExplicitlyDisabledProfilesKeepRawHistory() throws Exception {
        for (String profile : List.of("", "contextCompression=OFF\n")) {
            try (var f = fixture(ApiFormat.OPENAI_CHAT, profile)) {
                var raw = fill(f, 16_001);
                f.turn("walk", QUESTION);
                assertTrue(f.calls("submit_chat_summary").isEmpty());
                assertEquals(raw, history(f.calls("plan_turn").getFirst()).stream().map(Message::text).toList());
            }
        }
    }

    /** 两个端点预算都合法。同一段历史只应触发小预算档位，以实际调用证明配置生效。 */
    @Test public void appliesTheBudgetOfEachEnabledProfile() throws Exception {
        for (int budget : List.of(4_096, 48_000)) {
            try (var f = fixture(ApiFormat.OPENAI_CHAT, ROLLING + "historyBudgetUnits=" + budget + "\n")) {
                f.inbound("profile", padded("一次古渡旅行", 1_900));
                f.inbound("profile", padded("最近的话", 1_300));
                f.turn("profile", QUESTION);
                assertEquals("budget=" + budget, budget == 4_096 ? 1 : 0, f.calls("submit_chat_summary").size());
            }
        }
    }

    /** 非法配置须在 load 时点名字段，独立执行，不启动会话、审计或模型服务。 */
    @Test public void rejectsInvalidCompressionSettingsAtConfigLoad() throws Exception {
        var invalid = Map.of(
                "contextCompression=rolling\n", "contextCompression",
                "contextCompression=OFF\nhistoryBudgetUnits=48000\n", "historyBudgetUnits",
                "historyBudgetUnits=4096\n", "historyBudgetUnits",
                ROLLING + "historyBudgetUnits=4095\n", "historyBudgetUnits",
                ROLLING + "historyBudgetUnits=48001\n", "historyBudgetUnits",
                ROLLING + "historyBudgetUnits=4096.5\n", "historyBudgetUnits");
        for (var example : invalid.entrySet()) {
            var error = assertThrows(example.getKey(), IllegalArgumentException.class, () ->
                    ChatCompactionFixture.config(folder.getRoot().toPath(), ApiFormat.OPENAI_CHAT,
                            URI.create("http://127.0.0.1:18081"), 5, example.getKey()));
            assertTrue(error.getMessage(), error.getMessage().contains(example.getValue()));
        }
    }

    /** 两种协议各自约束候选输出。普通请求在相同位置交付独立 user 提要、原始角色与本轮问题。 */
    @Test public void bothProtocolsSendTheSameHistoryInAnIndependentUserMessage() throws Exception {
        List<String> firstProjection = null;
        for (ApiFormat format : List.of(ApiFormat.OPENAI_CHAT, ApiFormat.GEMINI)) {
            try (var f = fixture(format, ROLLING)) {
                var raw = fill(f, 16_000);
                f.summary = call -> CANDIDATE;
                f.turn("walk", QUESTION);
                assertEquals(1, f.calls("submit_chat_summary").size());
                checkStructuredOutput(format, f.calls("submit_chat_summary").getFirst().body());
                var plan = f.calls("plan_turn").getFirst().messages();
                assertEquals(List.of("system", "user", "user", format == ApiFormat.GEMINI ? "model" : "assistant", "user"),
                        plan.stream().map(Message::role).toList());
                assertTrue(plan.getFirst().text().contains(ChatCompactionFixture.PERSONA));
                assertFalse(plan.getFirst().text().contains(TOPIC));
                assertTrue(plan.get(1).text().contains("同会话历史提要"));
                assertTrue(plan.get(1).text().contains(TOPIC));
                assertEquals(raw.get(1), plan.get(2).text());
                assertEquals(raw.get(2), plan.get(3).text());
                assertEquals(QUESTION, plan.get(4).text());
                var projection = plan.subList(1, 5).stream().map(Message::text).toList();
                if (firstProjection == null) firstProjection = projection;
                else assertEquals(firstProjection, projection);
            }
        }
    }

    /** 两个真实适配器都须传播窗口错误。回环供应商以空白分词判断自己的试验窗口。 */
    @Test public void bothProtocolsReportAnOversizedCurrentTurnWithItsFullQuestion() throws Exception {
        String question = "起点🧭 " + "渡口 ".repeat(18_000) + " 终点⛵";
        for (ApiFormat format : List.of(ApiFormat.OPENAI_CHAT, ApiFormat.GEMINI)) {
            try (var f = fixture(format, ROLLING)) {
                f.plan = call -> {
                    long tokens = call.messages().stream().map(Message::text)
                            .flatMap(text -> Arrays.stream(text.split("\\s+"))).count();
                    if (tokens > 12_000) throw new ChatCompactionFixture.ProviderError(400);
                    return "{\"kind\":\"REPLY\",\"query\":\"\"}";
                };
                var error = assertThrows(IOException.class, () -> f.turn("window", question));
                assertEquals((format == ApiFormat.GEMINI ? "Gemini" : "Chat Worker") + " 返回 HTTP 400", error.getMessage());
                assertEquals(question, f.calls("plan_turn").getFirst().messages().getLast().text());
                assertTrue(f.calls("submit_chat_summary").isEmpty());
                assertTrue(f.calls("reply").isEmpty());
            }
        }
    }

    private ChatCompactionFixture fixture(ApiFormat format, String profile) throws IOException {
        return new ChatCompactionFixture(folder.newFolder().toPath(), format, profile);
    }

    /** 最老的用户发言可整体压缩，较近的一问一答保持真实送达的因果链。 */
    private static List<String> fill(ChatCompactionFixture f, int oldUnits) throws IOException {
        var texts = List.of(padded("旧路线🧭：过桥后走北岸小径。", oldUnits),
                padded("最近一问：明天在哪个渡口碰面？", 11_000), padded("最近一答：到南岸码头找我。", 9_000));
        f.inbound("walk", texts.get(0));
        int question = f.inbound("walk", texts.get(1));
        int draft = f.audit.write("walk", question, "chat-worker", "chat_worker_reply", Map.of("text", texts.get(2)));
        f.audit.write("walk", draft, "console", "console_response_written", Map.of());
        return texts;
    }

    private static String padded(String start, int units) {
        return start + "舟".repeat(units - start.length());
    }

    private static List<Message> history(Call call) {
        var messages = call.messages();
        return messages.subList(1, messages.size() - 1);
    }

    /** OpenAI 只能调用提交候选这一个函数，Gemini 使用 JSON 响应模式。两端的必填字段相同。 */
    private static void checkStructuredOutput(ApiFormat format, JsonObject request) {
        JsonObject schema;
        if (format == ApiFormat.OPENAI_CHAT) {
            assertEquals(1, request.getAsJsonArray("tools").size());
            var tool = request.getAsJsonArray("tools").get(0).getAsJsonObject();
            assertEquals("function", tool.get("type").getAsString());
            assertEquals("submit_chat_summary", tool.getAsJsonObject("function").get("name").getAsString());
            var choice = request.getAsJsonObject("tool_choice");
            assertEquals("function", choice.get("type").getAsString());
            assertEquals("submit_chat_summary", choice.getAsJsonObject("function").get("name").getAsString());
            schema = tool.getAsJsonObject("function").getAsJsonObject("parameters");
        } else {
            assertTrue(!request.has("tools") || request.getAsJsonArray("tools").isEmpty());
            var generation = request.getAsJsonObject("generationConfig");
            assertEquals("application/json", generation.get("responseMimeType").getAsString());
            schema = generation.getAsJsonObject(generation.has("responseSchema") ? "responseSchema" : "responseJsonSchema");
        }
        assertEquals(Set.of("items", "closed"), schema.getAsJsonArray("required").asList().stream()
                .map(value -> value.getAsString()).collect(Collectors.toSet()));
    }
}
