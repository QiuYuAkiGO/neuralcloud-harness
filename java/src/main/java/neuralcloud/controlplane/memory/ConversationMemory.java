package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ResidentMemory.Kind;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConversationMemory {
    private static final int RECENT_LIMIT = 6;
    private static final String DURABLE = "memory_durable";
    private final AuditLog audit;
    private final ResidentMemory memory;

    public ConversationMemory(AuditLog audit, ResidentMemory memory) {
        this.audit = audit;
        this.memory = memory;
    }

    public int rememberInbound(Trace trace, String text) throws IOException, InterruptedException {
        return remember(trace, trace.request(), Kind.OBSERVATION, text);
    }

    public record Recall(List<String> messages, int eventId) {}

    public Recall recent(Trace trace, int causedBy) throws IOException, InterruptedException {
        var scope = trace.scope();
        var records = audit.events(scope.chain()).reversed().stream()
                .filter(event -> DURABLE.equals(event.type()))
                .filter(event -> !scope.runId().equals(event.runId()) || event.eventId() < trace.request())
                .map(event -> event.data().getAsJsonObject())
                .filter(event -> scope.sessionId().equals(event.get("sessionId").getAsString()))
                .map(event -> event.get("recordId").getAsString())
                .distinct().limit(RECENT_LIMIT).toList();
        var messages = new ArrayList<String>();
        var recalled = new ArrayList<String>();
        // 从记忆服务重读同会话已有持久回执的记录，以服务当前的可见状态为准。
        for (String id : records.reversed()) {
            var value = memory.read(scope.sessionId(), id);
            if (value.isPresent()) {
                messages.add(value.orElseThrow());
                recalled.add(id);
            }
        }
        int event = trace.write(causedBy, "memory", "memory_recall", Map.of("recordIds", recalled));
        return new Recall(List.copyOf(messages), event);
    }

    public void rememberDelivered(Trace trace, int delivered, String text) throws IOException {
        try {
            remember(trace, delivered, Kind.SELF_EXPRESSION, text);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            deliveryWriteFailed(trace, delivered, error);
        } catch (IOException error) {
            deliveryWriteFailed(trace, delivered, error);
        }
    }

    private static void deliveryWriteFailed(Trace trace, int delivered, Exception error) throws IOException {
        // 回复已有发送回执。记忆写入失败单独记账，渠道不得重新发送。
        error.printStackTrace(System.err);
        trace.write(delivered, "memory", "memory_write_failed", Map.of("detail", error.toString()));
    }

    private int remember(Trace trace, int cause, Kind kind, String text) throws IOException, InterruptedException {
        var scope = trace.scope();
        var source = audit.events(scope.chain()).stream()
                .filter(event -> event.eventId() == cause && scope.runId().equals(event.runId()))
                .findFirst().orElseThrow();
        var receipt = memory.ingest(scope.sessionId(), scope.runId() + ":" + cause,
                Instant.parse(source.recordedAt()), kind, text);
        return trace.write(cause, "memory", DURABLE, Map.of("sessionId", scope.sessionId(), "kind", kind.name(),
                "recordId", receipt.recordId(), "revision", receipt.revision(), "sequence", receipt.sequence()));
    }
}
