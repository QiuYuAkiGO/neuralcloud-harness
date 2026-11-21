package neuralcloud.controlplane.console;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConsoleAuditTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void auditChainSurvivesRestart() throws Exception {
        var directory = folder.getRoot().toPath();
        String firstRun;
        try (var audit = AuditLog.open(directory)) {
            firstRun = audit.runId();
            int request = audit.write("session-1", null, "master", "operator_message", Map.of("text", "周五 15:00 开会"));
            audit.write("session-1", request, "master", "master_completion", Map.of("text", "收到"));
            audit.write("session-2", null, "router", "operator_message", Map.of("text", "最后几点开会？"));
        }

        try (var audit = AuditLog.open(directory)) {
            audit.write("session-1", null, "chat-worker", "operator_message", Map.of("text", "更正，改为周六 10:00"));

            var chains = audit.chains();
            assertEquals(List.of("session-1", "session-2"), chains.stream().map(AuditLog.Chain::chain).toList());
            assertEquals(List.of("master", "chat-worker"), chains.getFirst().nodes());

            var events = audit.events("session-1");
            assertEquals(3, events.size());
            assertNull(events.get(0).causedBy());
            assertEquals(firstRun, events.get(1).runId());
            assertEquals(Integer.valueOf(events.get(0).eventId()), events.get(1).causedBy());
            assertEquals("更正，改为周六 10:00", events.get(2).data().getAsJsonObject().get("text").getAsString());
        }
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    public void metricsKeepOnlySelectedSamples() {
        String body = """
                # HELP vllm:num_requests_running Number of requests running.
                # TYPE vllm:num_requests_running gauge
                vllm:num_requests_running{engine="0",model_name="qwen35-08b-post"} 1.0
                vllm:num_requests_waiting{engine="0",model_name="qwen35-08b-post"} 0.0
                vllm:time_to_first_token_seconds_bucket{le="0.001"} 0.0
                process_resident_memory_bytes 1.2e+09
                """;
        assertEquals(List.of(
                new NodeMonitor.Sample("vllm:num_requests_running", "engine=\"0\",model_name=\"qwen35-08b-post\"", "1.0"),
                new NodeMonitor.Sample("vllm:num_requests_waiting", "engine=\"0\",model_name=\"qwen35-08b-post\"", "0.0")),
                NodeMonitor.samples(body, NodeMonitor.MetricsFormat.VLLM));
    }
}
