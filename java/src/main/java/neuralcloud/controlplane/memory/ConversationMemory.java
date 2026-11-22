package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;

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

    public MemoryCall bind(Trace trace, MemoryCall.Caller caller, int parent) {
        return new MemoryCall(this, trace, caller, parent);
    }

    // matchCount 只计算本次检索命中的记录。回读说明和近期对话也可作为材料交给 Worker。
    record Recall(
            List<String> messages, int matchCount, int eventId, List<MemoryJournal.Read> bindings) {
        Recall(List<String> messages, int count, int event) {
            this(messages, count, event, List.of());
        }
    }

    ResidentV2 v2() {
        return memory instanceof ResidentV2 v ? v : null;
    }

    Recall retrieve(Trace trace, int event, QueryKind kind, String query, String role, String input)
            throws IOException, InterruptedException {
        ResidentV2 v = v2();
        if (v == null)
            return switch (kind) {
                case HISTORY -> recall(trace, event, query);
                case SELF_IDENTITY -> identity(trace, event);
                case SELF_HISTORY -> selfHistory(trace, event, query);
            };
        var snap = v.lookup(trace.scope(), role, kind, query, receivedAt(trace));
        var messages = new ArrayList<String>();
        var rows = new ArrayList<MemoryJournal.Read>();
        for (var item : snap.items()) {
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))) continue;
            rows.add(ResidentV2.binding(trace.scope(), role, kind.name(), item, snap, input));
            messages.add(ResidentV2.render(item, kind.name()));
        }
        int count = rows.size();
        if (!v.scopedRecall() && kind == QueryKind.HISTORY) {
            messages.addFirst("本次只回读了当前会话的近期对话，共 " + count + " 条。");
            count = 0;
        }
        if (snap.assessment().equals("CONFLICT")) messages.addFirst("当前证据存在冲突。");
        if (snap.assessment().equals("INCOMPLETE")) messages.addFirst("目前依据不足以确认执行结果。");
        int logged =
                trace.write(
                        event,
                        "memory",
                        "memory_recall",
                        Map.of(
                                "recordIds",
                                rows.stream().map(MemoryJournal.Read::id).toList(),
                                "bindings",
                                rows,
                                "stream_id",
                                snap.stream(),
                                "read_seq",
                                snap.seq(),
                                "assessment",
                                snap.assessment()));
        return new Recall(List.copyOf(messages), count, logged, List.copyOf(rows));
    }

    public enum QueryKind { HISTORY, SELF_IDENTITY, SELF_HISTORY }

    public boolean canSearchSelfHistory(Trace.Scope scope) {
        return memory.scopedRecall() && scope.qq().isPresent();
    }

    Recall identity(Trace trace, int causedBy) throws IOException, InterruptedException {
        var match = memory.selfIdentity(trace.scope());
        var messages = match.stream().map(ResidentMemory.Match::message).toList();
        int event = trace.write(causedBy, "memory", "memory_recall",
                Map.of("recordIds", match.stream().map(ResidentMemory.Match::recordId).toList()));
        return new Recall(messages, messages.size(), event);
    }

    Recall selfHistory(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
        var matches = memory.selfHistory(trace.scope().qq().orElseThrow(), text, receivedAt(trace));
        return historyResult(trace, causedBy, matches);
    }

    Recall recall(Trace trace, int causedBy, String text) throws IOException, InterruptedException {
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
        return Instant.parse(audit.event(trace.scope(), trace.request()).recordedAt());
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

}
