package neuralcloud.controlplane.evaluation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import neuralcloud.controlplane.master.Master;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MasterEvaluationTest {
    private static final String QUERIES = """
            请查找当前会话中关于会议改期的事实。
            请查找当前会话中关于会议改期的印象。
            """;
    private static final String DECISION = """
            我确认会议定在周日14时。
            旧提醒待核实。
            我引用证据1。
            我暂无印象。
            请核对旧提醒。
            """;
    private static final String UNKNOWN = """
            我暂时无法确认会议时间。
            旧提醒待核实。
            本轮无可引用证据。
            我暂无印象。
            """;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void linksDecisionToTheMemoryReturnAndOriginalVersion() throws Exception {
        List<JsonObject> events = evaluate("paired-sunday", 0, QUERIES, DECISION);
        JsonObject citation = event(events, "decision_evidence").getAsJsonArray("data").get(0).getAsJsonObject();
        JsonObject returned = event(events, "memory_result");
        assertEquals("sunday-change", citation.get("id").getAsString());
        assertEquals(2, citation.get("revision").getAsInt());
        assertEquals(returned.get("eventId"), citation.get("returnEventId"));
        assertEquals(event(events, "decision").get("eventId"), event(events, "decision_evidence").get("causedBy"));
        assertEquals(2, events.stream().filter(e -> e.get("type").getAsString().equals("memory_query"))
                .map(e -> e.get("causedBy")).filter(event(events, "model_output").get("eventId")::equals).count());
        assertEquals(2, events.stream().filter(e -> e.get("type").getAsString().equals("model_input")).toList()
                .getLast().getAsJsonObject("data").getAsJsonArray("memoryResultEventIds").size());
    }

    @Test
    public void refusesAnEarlyDecisionAndAnUnreturnedPrivateCitation() throws Exception {
        List<JsonObject> early = evaluate("paired-sunday", 1, DECISION);
        assertEquals("RETRIEVAL", event(early, "case_result").getAsJsonObject("data").get("category").getAsString());
        List<JsonObject> privateCase = evaluate("private-evidence", 1, QUERIES, DECISION);
        assertEquals("EVIDENCE", event(privateCase, "case_result").getAsJsonObject("data").get("category").getAsString());
        assertEquals("EMPTY", event(privateCase, "memory_result").getAsJsonObject("data").get("outcome").getAsString());
        assertFalse(privateCase.toString().contains("红松"));
        evaluate("private-evidence", 0, QUERIES, UNKNOWN + "请等待群内出现明确改期通知再确认。");
    }

    @Test
    public void javaHandlesPureEmptyLookupButMasterExplainsMismatchedResults() throws Exception {
        List<JsonObject> empty = evaluate("empty-facts", 0, QUERIES);
        assertEquals("JAVA_HANDLED", event(empty, "case_result").getAsJsonObject("data").get("category").getAsString());
        var handled = event(empty, "java_no_data").getAsJsonObject("data");
        assertEquals(2, handled.getAsJsonArray("memoryResultEventIds").size());

        List<JsonObject> mismatch = evaluate("mismatched-facts", 0, QUERIES,
                UNKNOWN + "请核实会议最终时间，因为查询返回的培训安排与本次会议改期不匹配。");
        var returned = event(mismatch, "memory_result").getAsJsonObject("data");
        assertEquals("MISMATCH", returned.get("outcome").getAsString());
        assertEquals(0, returned.getAsJsonArray("evidence").size());
        evaluate("mismatched-facts", 1, QUERIES, UNKNOWN + "请核实会议最终时间。");
    }

    @Test
    public void distinguishesMeetingConfirmationFromReminderOutcome() throws Exception {
        String conclusion = """
                我确认会议改到周六11时。
                旧提醒待核实。
                我引用证据1、证据2。
                我暂无印象。
                """;
        evaluate("attempt-without-receipt", 0, QUERIES, conclusion
                + "请先确认会议改期，核对旧提醒清单，等有结果后再告知提醒状态。若取消失败，也请如实告知。");
        evaluate("attempt-without-receipt", 1, QUERIES, conclusion
                + "请先确认会议改期，等取得成功回执后再告知提醒状态。");
    }

    @Test
    public void filtersMarkedReasoningAndKeepsTheDecisionLinked() throws Exception {
        List<JsonObject> events = evaluate("paired-sunday", 0, "<think>内部文本</think>\n" + QUERIES,
                "内部前缀</think>\n" + DECISION + "<think>内部尾部");
        var filters = events.stream().filter(e -> e.get("type").getAsString().equals("reasoning_filtered")).toList();
        assertEquals(2, filters.size());
        assertEquals(2, filters.getFirst().getAsJsonObject("data").get("markerCount").getAsInt());
        assertEquals(1, filters.getLast().getAsJsonObject("data").get("unclosedOpenings").getAsInt());
        assertEquals(1, filters.getLast().getAsJsonObject("data").get("unexpectedClosings").getAsInt());
        assertEquals(filters.getFirst().get("eventId"), event(events, "model_output").get("causedBy"));
        assertFalse(events.toString().contains("内部文本"));
        assertFalse(events.toString().contains("内部前缀"));
        assertFalse(events.toString().contains("内部尾部"));
        assertEquals("REVIEW_REQUIRED", event(events, "case_result").getAsJsonObject("data").get("category").getAsString());
    }

    private List<JsonObject> evaluate(String caseId, int expectedExit, String... responses) throws Exception {
        JsonObject source;
        try (var reader = new InputStreamReader(getClass().getResourceAsStream(
                "/fixtures/master-meeting-evaluation.json"), StandardCharsets.UTF_8)) {
            source = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray selected = new JsonArray();
        for (var scenario : source.getAsJsonArray("cases")) {
            if (scenario.getAsJsonObject().get("id").getAsString().equals(caseId)) {
                selected.add(scenario);
            }
        }
        source.add("cases", selected);
        Path directory = temporary.newFolder().toPath();
        Path fixtures = Files.writeString(directory.resolve("case.json"), source.toString());
        Path trace = directory.resolve("trace.jsonl");
        var scripted = new ArrayDeque<>(List.of(responses));
        var settings = new Master.Settings(URI.create("http://localhost/v1/completions"),
                "test-master", HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(3), "test", 1, 0, null);
        String rolePrompt = "你是 Master。先查证，再判断。";
        var evaluation = new MasterEvaluation((prompt, maxTokens, temperature, stop) -> {
            assertTrue(prompt.startsWith(rolePrompt + "\n\n"));
            return new Master.Completion(scripted.removeFirst(), "stop", false);
        }, fixtures, rolePrompt);
        assertEquals(expectedExit, evaluation.run(trace, settings));
        assertEquals(0, scripted.size());
        return Files.readAllLines(trace).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject event(List<JsonObject> events, String type) {
        return events.stream().filter(record -> record.get("type").getAsString().equals(type)).findFirst().orElseThrow();
    }
}
