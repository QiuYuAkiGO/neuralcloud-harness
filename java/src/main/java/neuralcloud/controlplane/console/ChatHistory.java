package neuralcloud.controlplane.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import neuralcloud.controlplane.console.AuditLog.Event;
import neuralcloud.controlplane.console.ConsoleServer.Trace.Scope;
import neuralcloud.controlplane.worker.DialogueMessage;
import neuralcloud.controlplane.worker.DialogueMessage.Speaker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按审计恢复顺序冻结渠道已经看见的发言。事件号只在本次 run 内解释，Memory 正文和模型草稿不入列。 */
final class ChatHistory {
    record Line(String key, Speaker kind, String speaker, String text, String request, String receipt, String participation,
                Map<String, Long> refs) {
        DialogueMessage message() { return new DialogueMessage(kind, text); }
    }
    record Frozen(String reset, List<Line> lines, Set<String> future, int skipped) {}

    static String key(Event event) { return event.runId() + ":" + event.eventId(); }

    /** 新事件记录稳定会话 ID；旧格式只有审计链，沿用该链的历史重置边界。 */
    static boolean resetFor(Event event, Scope scope) {
        if (!event.type().equals("memory_context_reset")) return false;
        var data = event.data().getAsJsonObject();
        return !data.has("sessionId") || scope.sessionId().equals(data.get("sessionId").getAsString());
    }

    static boolean inbound(Event event, boolean qq) {
        return qq ? event.type().equals("qq_message") && event.node().equals("openqq")
                : event.type().equals("operator_message")
                    && (event.node().equals("chat-worker") || event.node().equals("conversation"));
    }

    /** 原文回放与滚动提要共用渠道送达判定。父事件取自同一会话、同一运行。 */
    static Event visible(Event event, Map<Integer, Event> run, boolean qq) {
        if (inbound(event, qq)) return event;
        Event parent = run.get(event.causedBy());
        if (qq) {
            if (!event.type().equals("qq_delivery") || !event.node().equals("openqq") || parent == null
                    || !parent.type().equals("qq_send_requested") || !parent.node().equals("openqq")) return null;
            parent = run.get(parent.causedBy());
        } else if (!event.type().equals("console_response_written") || !event.node().equals("console")) return null;
        return parent != null && parent.type().equals("chat_worker_reply") && parent.node().equals("chat-worker")
                && !event.data().getAsJsonObject().has("source_refs") ? parent : null;
    }

    /** reset 可在本轮入站之后产生。此时旧前缀整体失效，较晚发言也不能倒灌进本轮。 */
    static Frozen freeze(List<Event> events, Scope scope, int request, String stream) throws java.io.IOException {
        String reset = "";
        int begin = 0, end = events.size();
        var future = new java.util.HashSet<String>();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (e.runId().equals(scope.runId()) && e.eventId() == request) end = i;
            if (i >= end) future.add(key(e));
            if (resetFor(e, scope)) { begin = i + 1; reset = key(e); }
        }
        if (end == events.size()) throw new java.io.IOException("会话审计缺少本轮请求截点");
        var rows = new ArrayList<Line>();
        var runs = new HashMap<String, Map<Integer, Event>>();
        var writes = new HashMap<String, Map<String, Long>>();
        var participation = new HashMap<String, String>();
        int skipped = 0;
        boolean qq = scope.channel().equals("openqq");
        boolean priorMutable = false;
        // 先索引截点以内的持久回执，用户观察和角色表达的版本回执通常晚于对应的渠道事件。
        for (int i = begin; i < end; i++) {
            var e = events.get(i);
            var run = runs.computeIfAbsent(e.runId(), ignored -> new HashMap<>());
            run.put(e.eventId(), e);
            if (e.type().equals("qq_participation") && qq) {
                for (Event p = e; p != null; p = run.get(p.causedBy())) {
                    if (inbound(p, true)) {
                        participation.put(key(p), e.data().getAsJsonObject().get("decision").getAsString());
                        break;
                    }
                    if (p.causedBy() == null || p.causedBy() >= p.eventId()) break;
                }
            }
            if (!e.type().equals("router_memory_write") && !e.type().equals("memory_durable")) continue;
            var d = e.data().getAsJsonObject();
            if (e.type().equals("router_memory_write") && d.has("sourceEventId"))
                writes.put(e.runId() + ":" + d.get("sourceEventId").getAsInt(), Map.of());
            if (e.type().equals("memory_durable") && d.has("sessionId") && d.get("sessionId").getAsString().equals(scope.sessionId())
                    && (!d.has("stream_id") || stream.equals(d.get("stream_id").getAsString()))) {
                Event parent = run.get(e.causedBy());
                if (parent != null && parent.type().equals("router_memory_write")
                        && parent.data().getAsJsonObject().has("sourceEventId"))
                    writes.put(e.runId() + ":" + parent.data().getAsJsonObject().get("sourceEventId").getAsInt(),
                            Map.of(d.get("recordId").getAsString(), d.get("revision").getAsLong()));
            }
        }
        for (int i = begin; i < end; i++) {
            Event delivery = events.get(i);
            var run = runs.get(delivery.runId());
            Event source = visible(delivery, run, qq);
            if (source == null) continue;
            var refs = new LinkedHashMap<String, Long>();
            String position = key(delivery);
            var own = writes.get(position);
            if (own != null && own.isEmpty()) { skipped++; continue; }
            if (own != null) refs.putAll(own);
            var data = delivery.data().getAsJsonObject();
            if (data.has("history_refs")) addRefs(refs, data.getAsJsonArray("history_refs"));
            var imported = data.getAsJsonArray("snapshot_refs");
            if (imported != null) addRefs(refs, imported);
            boolean user = source == delivery;
            String root = null, receipt = null, dispatch = null;
            boolean eligible = !data.has("source_refs") && !source.data().getAsJsonObject().has("source_refs");
            for (Event p = source; p != null && eligible; p = run.get(p.causedBy())) {
                if (inbound(p, qq)) { root = key(p); break; }
                if (p.causedBy() != null && p.causedBy() >= p.eventId()) { eligible = false; break; }
                switch (p.type()) {
                    case "chat_worker_image_input", "chat_worker_image_recognition", "worker_dispatch",
                         "subject_conflict", "memory_context_reset" -> eligible = false;
                    case "memory_recall" -> {
                        var d = p.data().getAsJsonObject();
                        eligible = d.has("stream_id") && stream.equals(d.get("stream_id").getAsString()) && bindRecall(d, scope, refs);
                    }
                    case "task_worker_result" -> {
                        var d = p.data().getAsJsonObject();
                        if (p.node().equals("task-worker") && d.has("capability") && d.has("status"))
                            receipt = d.get("capability").getAsString();
                    }
                    case "task_worker_dispatch" -> { if (p.node().equals("task-worker")) dispatch = key(p); }
                    case "chat_worker_turn_plan" -> {
                        var d = p.data().getAsJsonObject();
                        // 旧回复缺少跨轮来源时，不能把依赖过可变资料的历史再次作为无依据的本地发言。
                        if (!data.has("history_refs") && d.has("dialogueMessages") && d.get("dialogueMessages").getAsInt() > 0
                                && priorMutable) eligible = false;
                    }
                }
            }
            if (!user && root != null && writes.containsKey(root)) {
                var inputRefs = writes.get(root);
                if (inputRefs.isEmpty()) eligible = false;
                mergeRefs(refs, inputRefs);
            }
            // 导入的旧快照需带已核实的原受众来源。截点前没有可追溯入站的普通草稿不能越过 reset。
            if (!eligible || (!user && root == null && (imported == null || imported.isEmpty()))) { skipped++; continue; }
            String who = !user ? "Misty" : scope.group().isEmpty() ? "用户"
                    : data.has("senderId") ? "群成员 " + data.get("senderId").getAsString() : "群内一位成员";
            rows.add(new Line(position, user ? Speaker.USER : Speaker.MISTY, who,
                    source.data().getAsJsonObject().get("text").getAsString(), root,
                    receipt != null && dispatch != null ? receipt : null, participation.get(position), Map.copyOf(refs)));
            priorMutable |= !refs.isEmpty();
        }
        return new Frozen(reset, List.copyOf(rows), Set.copyOf(future), skipped);
    }

    /** 使用实际被角色消费的绑定作为版本依据。跨会话、临时授权、时效资料均不进入提要。 */
    private static boolean bindRecall(JsonObject recall, Scope scope, Map<String, Long> refs) {
        if (!(recall.get("bindings") instanceof JsonArray bindings)) return false;
        for (var raw : bindings) {
            var item = raw.getAsJsonObject().getAsJsonObject("item");
            if (!scope.conversationId().equals(item.get("citation_scope").getAsString())
                    || !neuralcloud.controlplane.memory.MemoryCall.permanentHistorySource(item)
                    || !(item.get("source_refs") instanceof JsonArray sources)) return false;
            addRef(refs, item.getAsJsonObject("ref"));
            addRefs(refs, sources);
        }
        return true;
    }

    private static void addRefs(Map<String, Long> refs, JsonArray sources) {
        for (var source : sources) addRef(refs, source.getAsJsonObject());
    }

    private static void addRef(Map<String, Long> refs, JsonObject ref) {
        long revision = ref.get("revision").getAsLong();
        refs.merge(ref.get("record_id").getAsString(), revision, (a, b) -> a.equals(b) ? a : -1L);
    }

    /** 同一依据的两个不同修订无法同时通过复核，保留冲突直至受众边界过滤。 */
    static void mergeRefs(Map<String, Long> refs, Map<String, Long> added) {
        added.forEach((id, version) -> refs.merge(id, version, (a, b) -> a.equals(b) ? a : -1L));
    }

}
