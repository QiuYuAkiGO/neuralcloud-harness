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
        assertEquals("NO_MATCH", event(privateCase, "memory_result").getAsJsonObject("data").get("outcome").getAsString());
        assertFalse(privateCase.toString().contains("红松"));
    }

    @Test
    public void withholdsOutputMarkedAsReasoning() throws Exception {
        List<JsonObject> events = evaluate("paired-sunday", 1, "<think>内部文本</think>");
        assertEquals("reasoning_markup",
                event(events, "model_output").getAsJsonObject("data").get("withheld").getAsString());
        assertFalse(events.toString().contains("内部文本"));
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
                "test-master", HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(3), "test", 1, 0);
        String rolePrompt = "你是 Master。先查证，再判断。";
        var evaluation = new MasterEvaluation((prompt, maxTokens, temperature, stop) -> {
            assertTrue(prompt.startsWith(rolePrompt + "\n\n"));
            return new Master.Completion(scripted.removeFirst(), "stop");
        }, fixtures, rolePrompt);
        assertEquals(expectedExit, evaluation.run(trace, settings));
        assertEquals(0, scripted.size());
        return Files.readAllLines(trace).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject event(List<JsonObject> events, String type) {
        return events.stream().filter(record -> record.get("type").getAsString().equals(type)).findFirst().orElseThrow();
    }
}
