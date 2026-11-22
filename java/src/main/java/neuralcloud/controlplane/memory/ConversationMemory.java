package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ResidentMemory.Kind;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return rememberInbound(trace, trace.request(), text);
    }

    public int rememberInbound(Trace trace, int accepted, String text) throws IOException, InterruptedException {
        return remember(trace, trace.request(), accepted, Kind.OBSERVATION, text);
    }

    // matchCount 只计算本次检索命中的记录。回读说明和近期对话也可作为材料交给 Worker。
    public record Recall(List<String> messages, int matchCount, int eventId) {}
    public enum QueryKind { HISTORY, SELF_IDENTITY, SELF_HISTORY }

    public boolean canSearchSelfHistory(Trace.Scope scope) {
        return memory.scopedRecall() && scope.qq().isPresent();
    }

    public Recall identity(Trace trace, int causedBy) throws IOException, InterruptedException {
        var match = memory.selfIdentity(trace.scope());
        var messages = match.stream().map(ResidentMemory.Match::message).toList();
        int event = trace.write(causedBy, "memory", "memory_recall",
                Map.of("recordIds", match.stream().map(ResidentMemory.Match::recordId).toList()));
        return new Recall(messages, messages.size(), event);
    }

    public Recall selfHistory(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
        if (!canSearchSelfHistory(trace.scope())) {
            throw new IOException("本人跨会话查询需要渠道确认的 QQ 私聊账号和生产记忆服务");
        }
        var matches = memory.selfHistory(trace.scope().qq().orElseThrow(), text, receivedAt(trace));
        return historyResult(trace, causedBy, matches);
    }

    public Recall recall(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
        var scope = trace.scope();
        if (memory.scopedRecall()) {
            return historyResult(trace, causedBy, memory.search(scope, text, receivedAt(trace)));
        }

        // recent 模式按持久回执重读近期对话，只提供上下文，话题命中计数为零。
        var records = audit.events(scope.chain()).reversed().stream()
                .filter(event -> DURABLE.equals(event.type()))
                .filter(event -> !scope.runId().equals(event.runId()) || event.eventId() < trace.request())
                .map(event -> event.data().getAsJsonObject())
                .filter(event -> scope.sessionId().equals(event.get("sessionId").getAsString()))
                .filter(memory::acceptsReceipt)
                .map(event -> event.get("recordId").getAsString())
                .distinct().limit(RECENT_LIMIT).toList();
        var recent = new LinkedHashMap<String, String>();
        for (String id : records.reversed()) {
            memory.read(scope, id).ifPresent(message -> recent.put(id, message));
        }
        int event = trace.write(causedBy, "memory", "memory_recall",
                Map.of("recordIds", List.copyOf(recent.keySet()), "historicalRecordIds", List.of()));
        var materials = new ArrayList<String>();
        materials.add("本次只回读了当前会话的近期对话，共 " + recent.size() + " 条。");
        materials.addAll(recent.values());
        return new Recall(List.copyOf(materials), 0, event);
    }

    private Instant receivedAt(Trace trace) {
        return Instant.parse(event(trace, trace.request()).recordedAt());
    }

    private static Recall historyResult(Trace trace, int causedBy, List<ResidentMemory.Match> found) throws IOException {
        // 两种范围都只将本轮的实际命中记入回执，样板句与记录 ID 按相同顺序对应。
        var matches = new LinkedHashMap<String, String>();
        for (var match : found) {
            matches.put(match.recordId(), match.message());
        }
        var ids = List.copyOf(matches.keySet());
        int event = trace.write(causedBy, "memory", "memory_recall", Map.of("recordIds", ids, "historicalRecordIds", ids));
        return new Recall(List.copyOf(matches.values()), ids.size(), event);
    }

    public void rememberDelivered(Trace trace, int delivered, String text) throws IOException {
        try {
            remember(trace, delivered, delivered, Kind.SELF_EXPRESSION, text);
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

    private int remember(Trace trace, int sourceId, int causedBy, Kind kind, String text)
            throws IOException, InterruptedException {
        var scope = trace.scope();
        var source = event(trace, sourceId);
        String messageId = scope.qq().map(qq -> source.data().getAsJsonObject().get("messageId").getAsString())
                .orElse("");
        var receipt = memory.ingest(scope, scope.runId() + ":" + sourceId, Instant.parse(source.recordedAt()), kind,
                text, messageId);
        return trace.write(causedBy, "memory", DURABLE, Map.of("sessionId", scope.sessionId(), "kind", kind.name(),
                "characterId", memory.character(), "recordId", receipt.recordId(), "revision", receipt.revision(),
                "sequence", receipt.sequence()));
    }

    private AuditLog.Event event(Trace trace, int id) {
        var scope = trace.scope();
        return audit.events(scope.chain()).stream()
                .filter(event -> event.eventId() == id && scope.runId().equals(event.runId()))
                .findFirst().orElseThrow();
    }
}
