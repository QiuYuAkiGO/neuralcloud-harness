package neuralcloud.controlplane.router;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.memory.ResidentMemory.Kind;
import neuralcloud.controlplane.memory.ResidentV2;

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

    public boolean supportsAttestation() {
        return memory instanceof neuralcloud.controlplane.memory.ResidentV2;
    }

    public boolean authorGrantsEnabled() {
        return memory instanceof ResidentV2 v2 && v2.authorGrantsEnabled();
    }

    public void verifyDelivery(Trace trace) throws IOException, InterruptedException {
        if (!trace.sharedSources().isEmpty() && memory instanceof ResidentV2 v2)
            v2.verifyDelivery(trace);
    }

    public com.google.gson.JsonObject authorDecision(Trace trace, long messageId, String command)
            throws IOException, InterruptedException {
        if (!(memory instanceof ResidentV2 v2)) throw new IOException("此渠道未启用原作者授权");
        return v2.authorDecision(trace.scope(), messageId, command);
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

    /** 仅宿主执行器可提交工具阶段，二者绑定同一个 actionId。模型生成的叙述不会进入这条写路径。 */
    public int rememberTool(
            Trace trace, int parent, String actionId, boolean completed, String content)
            throws IOException, InterruptedException {
        if (!(memory instanceof ResidentV2 v2)) return parent;
        String kind = completed ? "TOOL_RESULT" : "TOOL_ATTEMPT";
        int writing =
                trace.write(
                        parent,
                        "router",
                        "router_memory_write",
                        Map.of("kind", kind, "actionId", actionId));
        var source = audit.event(trace.scope(), writing);
        try {
            var receipt =
                    v2.ingestTool(
                            trace.scope(),
                            trace.scope().runId() + ":" + writing,
                            Instant.parse(source.recordedAt()),
                            completed,
                            actionId,
                            content);
            return trace.write(
                    writing,
                    "memory",
                    "memory_durable",
                    Map.of(
                            "kind",
                            kind,
                            "actionId",
                            actionId,
                            "recordId",
                            receipt.recordId(),
                            "revision",
                            receipt.revision(),
                            "sequence",
                            receipt.sequence()));
        } catch (IOException | InterruptedException error) {
            trace.write(
                    writing,
                    "memory",
                    "memory_write_failed",
                    Map.of("kind", kind, "detail", error.toString()));
            throw error;
        }
    }

    private static int request(Trace trace, int parent, int source, Kind kind) throws IOException {
        return trace.write(parent, "router", "router_memory_write", Map.of("kind", kind.name(), "sourceEventId", source));
    }

    private int persist(Trace trace, int writing, int sourceId, Kind kind, String text)
            throws IOException, InterruptedException {
        var scope = trace.scope();
        var source = audit.event(scope, sourceId);
        String messageId =
                scope.qq().isPresent() || scope.group().isPresent()
                        ? source.data().getAsJsonObject().get("messageId").getAsString()
                        : "";
        ResidentMemory.Receipt receipt;
        try {
            String event = scope.runId() + ":" + sourceId;
            if (memory instanceof neuralcloud.controlplane.memory.ResidentV2 v2) {
                var origin = source.data().getAsJsonObject().getAsJsonObject("observation");
                if (kind == Kind.SELF_EXPRESSION
                        && source.data().getAsJsonObject().has("source_refs")) {
                    origin = new com.google.gson.JsonObject();
                    origin.add("source_refs", source.data().getAsJsonObject().get("source_refs"));
                }
                receipt =
                        v2.ingestEvent(
                                scope,
                                event,
                                Instant.parse(source.recordedAt()),
                                kind,
                                text,
                                messageId,
                                origin);
            } else {
                receipt =
                        memory.ingest(
                                scope,
                                event,
                                Instant.parse(source.recordedAt()),
                                kind,
                    text, messageId);
            }
        } catch (IOException | InterruptedException error) {
            trace.write(writing, "memory", "memory_write_failed", Map.of("kind", kind.name(), "detail", error.toString()));
            throw error;
        }
        return trace.write(writing, "memory", "memory_durable", Map.of("sessionId", scope.sessionId(), "kind", kind.name(),
                "characterId", memory.character(), "recordId", receipt.recordId(), "revision", receipt.revision(),
                "sequence", receipt.sequence()));
    }
}
