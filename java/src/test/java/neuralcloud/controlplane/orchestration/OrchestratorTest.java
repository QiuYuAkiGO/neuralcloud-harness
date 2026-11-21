package neuralcloud.controlplane.orchestration;

import neuralcloud.controlplane.orchestration.Orchestrator.Plan;
import neuralcloud.controlplane.orchestration.Orchestrator.Receipt;
import neuralcloud.controlplane.orchestration.Orchestrator.Scope;
import neuralcloud.controlplane.orchestration.Orchestrator.Step;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

public class OrchestratorTest {
    private final Scope scope = new Scope("test-conversation", UUID.randomUUID());

    @Test
    public void executesUnorderedStepsWithOnlyTheirDeclaredReceipts() throws Exception {
        var read = new Step("read", "storage", "Read the document", List.of());
        var count = new Step("count", "analysis", "Count its lines", List.of("read"));
        var log = new Step("log", "audit", "Log the request", List.of());
        var dispatched = new ArrayList<Step>();
        var readReceipt = new Receipt<>(scope, read.id(), "line one");

        var receipts = new Orchestrator().<String>execute(new Plan(scope, List.of(count, log, read)),
                (current, step, dependencies) -> {
                    assertEquals(scope, current);
                    dispatched.add(step);
                    if (step.equals(count)) {
                        assertEquals(Map.of("read", readReceipt), dependencies);
                        return new Receipt<>(scope, count.id(), "1");
                    }
                    assertEquals(Map.of(), dependencies);
                    return step.equals(read) ? readReceipt : new Receipt<>(scope, log.id(), "recorded");
                });

        assertEquals(List.of(log, read, count), dispatched);
        assertEquals(Map.of("read", readReceipt, "count", new Receipt<>(scope, count.id(), "1"),
                "log", new Receipt<>(scope, log.id(), "recorded")), receipts);
    }

    @Test
    public void rejectsInvalidDependencyGraphsBeforeDispatch() {
        var independent = new Step("ready", "worker", "Independent work", List.of());
        var waiting = new Step("blocked", "worker", "Wait for a result", List.of("missing"));
        var cycle = new Step("missing", "worker", "Create a cycle", List.of("blocked"));
        Orchestrator.Dispatcher<String> dispatcher = (current, step, dependencies) -> {
            fail("Invalid plans must be rejected before dispatching " + step.id());
            return null;
        };

        assertThrows(IllegalArgumentException.class,
                () -> new Orchestrator().execute(new Plan(scope, List.of(independent, waiting)), dispatcher));
        assertThrows(IllegalArgumentException.class,
                () -> new Orchestrator().execute(new Plan(scope, List.of(independent, waiting, cycle)), dispatcher));
    }
}
