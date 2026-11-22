package neuralcloud.controlplane.router;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.memory.ResidentMemory.Kind;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Router 的记忆写入入口。入站消息与渠道确认的回复分别以各自的原始事件作为幂等键。 */
public final class RouterMemoryWriter {
    private final AuditLog audit;
    private final ResidentMemory memory;

    public RouterMemoryWriter(AuditLog audit, ResidentMemory memory) {
        this.audit = audit;
        this.memory = memory;
    }

    public int rememberInbound(Trace trace, int accepted, String text) throws IOException, InterruptedException {
        int writing = request(trace, accepted, trace.request(), Kind.OBSERVATION);
        return persist(trace, writing, trace.request(), Kind.OBSERVATION, text);
    }

    public void rememberDelivered(Trace trace, int delivered, String text) throws IOException {
        int writing = request(trace, delivered, delivered, Kind.SELF_EXPRESSION);
        try {
            persist(trace, writing, delivered, Kind.SELF_EXPRESSION, text);
        } catch (InterruptedException | IOException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 渠道已经确认送达。写入失败单独记账，渠道不得因此重发。
            error.printStackTrace(System.err);
        }
    }

    private static int request(Trace trace, int parent, int source, Kind kind) throws IOException {
        return trace.write(parent, "router", "router_memory_write", Map.of("kind", kind.name(), "sourceEventId", source));
    }

    private int persist(Trace trace, int writing, int sourceId, Kind kind, String text)
            throws IOException, InterruptedException {
        var scope = trace.scope();
        var source = audit.event(scope, sourceId);
        String messageId = scope.qq().map(qq -> source.data().getAsJsonObject().get("messageId").getAsString())
                .orElse("");
        ResidentMemory.Receipt receipt;
        try {
            receipt = memory.ingest(scope, scope.runId() + ":" + sourceId, Instant.parse(source.recordedAt()), kind,
                    text, messageId);
        } catch (IOException | InterruptedException error) {
            trace.write(writing, "memory", "memory_write_failed", Map.of("kind", kind.name(), "detail", error.toString()));
            throw error;
        }
        return trace.write(writing, "memory", "memory_durable", Map.of("sessionId", scope.sessionId(), "kind", kind.name(),
                "characterId", memory.character(), "recordId", receipt.recordId(), "revision", receipt.revision(),
                "sequence", receipt.sequence()));
    }
}
