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
    private static final Master.ChatTurn QUERY = call("query_memory", """
            {"sources":["FACT","IMPRESSION"]}
            """);

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void retriesOnlyMissingItemsAndLinksEvidence() throws Exception {
        Master.ChatTurn partial = call("submit_results", """
                {"meetingTime":"周日14时","evidenceLabels":[1],"reminderStatus":"UNKNOWN","impression":"无",
                 "verifyReminder":true}
                """);
        Master.ChatTurn completion = call("submit_results", """
                {"actionIntent":"请核对旧提醒，等实际结果后再告知。","waitReminderResult":true}
                """);
        var events = evaluate("paired-sunday", 0, QUERY, partial, completion);
        var retry = event(events, "submission_retry");
        assertEquals("[\"actionIntent\",\"waitReminderResult\"]", retry.getAsJsonObject("data").get("items").toString());
        var decision = event(events, "decision");
        assertEquals("周日14时", decision.getAsJsonObject("data").get("meetingTime").getAsString());
        var cite = event(events, "decision_evidence").getAsJsonArray("data").get(0).getAsJsonObject();
        assertEquals("sunday-change", cite.get("id").getAsString());
        assertEquals(event(events, "memory_result").get("eventId"), cite.get("returnEventId"));
        var inputs = events.stream().filter(e -> e.get("type").getAsString().equals("model_input")).toList();
        assertEquals(3, inputs.size());
        assertTrue(inputs.getLast().get("data").toString().contains("仅补交"));
        assertTrue(inputs.getLast().get("data").toString().contains("tool_call_id"));
    }

    @Test
    public void freeTextDoesNotCountAsSubmissionAndRetryIsBounded() throws Exception {
        var text = new Master.ChatTurn(new Master.Completion("会议定在周日14时。", "stop", false), List.of());
        var events = evaluate("paired-sunday", 1, QUERY, text, text);
        assertEquals("INCOMPLETE_RESULTS", event(events, "case_result").getAsJsonObject("data").get("category").getAsString());
        assertFalse(events.stream().anyMatch(e -> e.get("type").getAsString().equals("decision")));
    }

    @Test
    public void refusesToOverwritePreviouslyAcceptedResults() throws Exception {
        var partial = call("submit_results", """
                {"meetingTime":"UNKNOWN"}
                """);
        var extra = call("submit_results", """
                {"meetingTime":"UNKNOWN","evidenceLabels":[]}
                """);
        var events = evaluate("private-evidence", 1, QUERY, partial, extra);
        var submits = events.stream().filter(e -> e.get("type").getAsString().equals("result_submission")).toList();
        assertEquals(1, submits.getLast().getAsJsonObject("data").get("unexpected").getAsInt());
        assertEquals("[\"evidenceLabels\"]", submits.getLast().getAsJsonObject("data").get("missing").toString());
    }

    @Test
    public void separatesJavaEmptyHandlingFromPrivateEvidenceRejection() throws Exception {
        var empty = evaluate("empty-facts", 0, QUERY);
        assertEquals(2, event(empty, "java_no_data").getAsJsonObject("data").getAsJsonArray("memoryResultEventIds").size());
        var wrong = call("submit_results", """
                {"meetingTime":"UNKNOWN","evidenceLabels":[1]}
                """);
        var rejected = evaluate("private-evidence", 1, QUERY, wrong);
        assertEquals("EVIDENCE", event(rejected, "case_result").getAsJsonObject("data").get("category").getAsString());
        assertFalse(rejected.toString().contains("红松"));
        evaluate("private-evidence", 0, QUERY, call("submit_results", """
                {"meetingTime":"UNKNOWN","evidenceLabels":[]}
                """));
    }

    @Test
    public void filtersReasoningInsideToolArgumentsAndAuditsTheRemoval() throws Exception {
        var submit = call("submit_results", """
                {"meetingTime":"UNKNOWN","evidenceLabels":[],"reminderStatus":"UNKNOWN","impression":"无",
                 "reason":"MISMATCH","verifyMeetingTime":true,
                 "actionIntent":"<think>内部信息</think>请核实会议时间，返回的数据与会议改期不匹配。"}
                """);
        var events = evaluate("mismatched-facts", 0, QUERY, submit);
        var filtered = event(events, "reasoning_filtered");
        assertEquals("actionIntent", filtered.getAsJsonObject("data").get("toolArgumentField").getAsString());
        assertEquals("MISMATCH", event(events, "memory_result").getAsJsonObject("data").get("outcome").getAsString());
        assertFalse(events.toString().contains("内部信息"));
    }

    private static Master.ChatTurn call(String name, String args) {
        return new Master.ChatTurn(new Master.Completion("", "tool_calls", false),
                List.of(new Master.ToolCall(name + "-" + args.hashCode(), name, args)));
    }

    private List<JsonObject> evaluate(String caseId, int expectedExit, Master.ChatTurn... responses) throws Exception {
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
        var settings = new Master.Settings(URI.create("http://localhost/v1/chat/completions"),
                "test-master", HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(3), "test", 1, 0, false);
        String rolePrompt = "你是 Master。先查证，再判断。";
        var evaluation = new MasterEvaluation((messages, tools, maxTokens, temperature) -> {
            assertEquals(rolePrompt, messages.getFirst().get("content"));
            return scripted.removeFirst();
        }, fixtures, rolePrompt);
        assertEquals(expectedExit, evaluation.run(trace, settings));
        assertEquals(0, scripted.size());
        return Files.readAllLines(trace).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject event(List<JsonObject> events, String type) {
        return events.stream().filter(record -> record.get("type").getAsString().equals(type)).findFirst().orElseThrow();
    }
}
