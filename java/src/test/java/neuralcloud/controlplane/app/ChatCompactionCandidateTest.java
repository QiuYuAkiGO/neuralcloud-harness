package neuralcloud.controlplane.app;

import com.google.gson.JsonObject;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 以候选原文和供应商结束标志分别刺激正式入口，观察审计游标及下游规划使用的实际提要。 */
@RunWith(Parameterized.class)
public class ChatCompactionCandidateTest {
    @Parameterized.Parameters(name = "{0}") public static List<ApiFormat> formats() {
        return List.of(ApiFormat.OPENAI_CHAT, ApiFormat.GEMINI);
    }

    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Rule public ErrorCollector errors = new ErrorCollector();
    private static final String PROFILE = "contextCompression=ROLLING\nhistoryBudgetUnits=4096\n";
    private static final String BASE = "前面聊过南渡口的路线。";
    private static final String NEXT = "后来又提到了芦苇坡。";
    private static final String VALID = """
            {"items":[{"kind":"TOPIC","text":"后来又提到了芦苇坡。","sources":[2]}],"closed":[]}
            """;
    private final ApiFormat format;
    public ChatCompactionCandidateTest(ApiFormat format) { this.format = format; }

    /** 已正常结束并按契约提交的第二批候选覆盖新来源，规划读到该次生效的版本。 */
    @Test public void completedStructuredOutputReplacesTheCommittedSnapshot() throws Exception {
        try (var f = new ChatCompactionFixture(folder.newFolder().toPath(), format, PROFILE)) {
            var base = baseline(f, "合法");
            f.summary = call -> VALID;
            f.turn("合法", "接着聊吧。");
            var committed = diagnostic(f, "合法");
            assertEquals("COMMITTED", committed.get("status").getAsString());
            assertTrue(committed.get("revision").getAsLong() > base.get("revision").getAsLong());
            assertNotEquals(base.get("covered"), committed.get("covered"));
            assertTrue(plannedSummary(f).contains(NEXT));
        }
    }

    private record Variant(String name, String candidate, boolean truncated) {}

    /** 每种错误从各自刚提交的同内容快照开始。截断例的 JSON 本身完整，仅改协议结束标记。 */
    @Test public void fieldTypeSyntaxAndProviderTruncationShareTheRejectionBoundary() throws Exception {
        var examples = List.of(
                new Variant("供应商截断", VALID, true),
                new Variant("顶层多余字段", VALID.replace("\"closed\":[]", "\"closed\":[],\"admin\":true"), false),
                new Variant("条目多余字段", VALID.replace("\"sources\":[2]", "\"sources\":[2],\"trust\":1"), false),
                new Variant("缺少结项数组", VALID.replace(",\"closed\":[]", ""), false),
                new Variant("条目类别不在枚举中", VALID.replace("TOPIC", "MEMORY"), false),
                new Variant("正文是数字", VALID.replace("\"" + NEXT + "\"", "87"), false),
                new Variant("来源是数字字符串", VALID.replace("[2]", "[\"2\"]"), false),
                new Variant("来源是非整数", VALID.replace("[2]", "[2.5]"), false),
                new Variant("结项字段是对象", VALID.replace("\"closed\":[]", "\"closed\":{}"), false),
                new Variant("字段名没有双引号", VALID.replace("\"items\":", "items:"), false));
        try (var f = new ChatCompactionFixture(folder.newFolder().toPath(), format, PROFILE)) {
            for (var example : examples) errors.checkSucceeds(() -> {
                String name = example.name();
                f.summaryEnvelope = response -> {};
                var base = baseline(f, name);
                f.summary = call -> example.candidate();
                if (example.truncated()) f.summaryEnvelope = this::truncate;
                f.turn(name, "接着聊吧。");
                var result = diagnostic(f, name);
                for (String field : List.of("revision", "covered", "offset"))
                    assertEquals(name + " " + field, base.get(field), result.get(field));
                String planning = plannedSummary(f);
                assertTrue(name, planning.contains(BASE));
                assertFalse(name, planning.contains(NEXT));
                assertEquals(name, "REJECTED", result.get("status").getAsString());
                return null;
            });
        }
    }

    /** 两大供应商使用各自的正式传输字段通告截断，保持候选正文、函数名称和全部参数完好。 */
    private void truncate(JsonObject response) {
        if (format == ApiFormat.GEMINI) response.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .addProperty("finishReason", "MAX_TOKENS");
        else response.getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("finish_reason", "length");
    }

    /** 首次只整理南渡口，补入两条近期原文后，芦苇坡成为第二批可整理的新来源。 */
    private static JsonObject baseline(ChatCompactionFixture f, String chain) throws Exception {
        f.inbound(chain, "我说的是南渡口。" + "远".repeat(1_900));
        f.inbound(chain, "还聊到芦苇坡。" + "近".repeat(1_500));
        f.summary = call -> "{\"items\":[{\"kind\":\"TOPIC\",\"text\":\"" + BASE + "\",\"sources\":[1]}],\"closed\":[]}";
        f.turn(chain, "收到。");
        var committed = diagnostic(f, chain);
        assertEquals("COMMITTED", committed.get("status").getAsString());
        f.inbound(chain, "最近补了一页。" + "新".repeat(900));
        f.inbound(chain, "又补了半页。" + "尾".repeat(700));
        return committed;
    }

    private static JsonObject diagnostic(ChatCompactionFixture f, String chain) {
        return f.audit.events(chain).stream().filter(event -> event.type().equals("chat_context_compaction"))
                .toList().getLast().data().getAsJsonObject();
    }

    private static String plannedSummary(ChatCompactionFixture f) {
        return f.calls("plan_turn").getLast().messages().stream().filter(m -> m.role().equals("user")
                && m.text().contains("同会话历史提要")).findFirst().orElseThrow().text();
    }
}
