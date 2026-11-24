package neuralcloud.controlplane.console;

import neuralcloud.controlplane.console.ChatContextStore.Head;
import neuralcloud.controlplane.console.ChatHistory.Frozen;
import neuralcloud.controlplane.console.ChatHistory.Line;
import neuralcloud.controlplane.console.ChatSummary.State;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.DialogueHistory;
import neuralcloud.controlplane.worker.DialogueMessage;
import neuralcloud.controlplane.worker.DialogueMessage.Speaker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 管理参与轮次的冻结、一次模型生成和条件提交。审计锁只用于快照边界和短事务。 */
final class RollingChatContext implements AutoCloseable {
    private static final String GAP = "较早的一段会话历史未展开。";
    private final AuditLog audit;
    private final ChatContextStore store;
    private final Set<String> preparing = ConcurrentHashMap.newKeySet();

    RollingChatContext(AuditLog audit) {
        this.audit = audit;
        this.store = new ChatContextStore(audit.directory());
    }

    private record View(State state, List<Line> lines, Map<String, Line> byKey, boolean future, int skipped) {}
    private record Rendered(DialogueHistory history, long gap, int rawCount) {}

    /** 使用审核顺序而非 UUID 排序。读取审计、SQLite 后执行的网络复核不持任何日志锁。 */
    private Frozen freeze(Trace trace, MemoryCall memory) throws IOException {
        return ChatHistory.freeze(audit.events(trace.scope().chain()), trace.scope(), trace.request(), stream(memory));
    }

    private static String stream(MemoryCall memory) { return memory == null ? "" : memory.historyStream(); }

    private static Map<String, Long> versions(MemoryCall memory, Set<String> ids) throws IOException {
        return memory == null || ids.isEmpty() ? Map.of() : memory.historyVersions(ids);
    }

    private static boolean authorized(Map<String, Long> refs, Map<String, Long> current) {
        return current.entrySet().containsAll(refs.entrySet());
    }

    /** 已覆盖的无关原文不必逐轮向 Memory 重读。快照失效时重新从可验证的原文选取。 */
    private View view(Trace trace, Frozen frozen, Head head, MemoryCall memory) throws IOException {
        var empty = State.empty(trace.scope().conversationId(), frozen.reset());
        State state = head.state();
        boolean future = state != null && state.covered() != null && frozen.future().contains(state.covered());
        var all = frozen.lines();
        var keys = new LinkedHashMap<String, Line>();
        all.forEach(line -> keys.put(line.key(), line));
        boolean usable = !future && usable(state, empty, keys)
                && (state.refs().isEmpty() || stream(memory).equals(state.stream()));
        Set<String> needed = new LinkedHashSet<>();
        List<Line> pending = all;
        if (usable && !state.covered().isEmpty()) {
            int start = ChatSummary.firstUncovered(state, all);
            pending = all.subList(start, all.size());
            needed.addAll(state.refs().keySet());
            needed.addAll(keys.get(state.covered()).refs().keySet());
        }
        for (var line : pending) needed.addAll(line.refs().keySet());
        var current = versions(memory, needed);
        if (usable && (!authorized(state.refs(), current) || (!state.covered().isEmpty()
                && !authorized(keys.get(state.covered()).refs(), current)))) {
            usable = false;
            for (var line : all) needed.addAll(line.refs().keySet());
            current = versions(memory, needed);
        }
        var allowed = new ArrayList<Line>();
        Set<String> pendingKeys = new LinkedHashSet<>();
        if (usable && !state.covered().isEmpty()) {
            pendingKeys.add(state.covered());
            for (var line : pending) pendingKeys.add(line.key());
            for (var item : state.items()) pendingKeys.addAll(item.origins());
            pendingKeys.addAll(state.closedProof());
        }
        int skipped = frozen.skipped();
        for (var line : all) {
            if (!pendingKeys.isEmpty() && !pendingKeys.contains(line.key())) continue;
            if (authorized(line.refs(), current)) allowed.add(line);
            else skipped++;
        }
        return new View(usable ? state : empty, List.copyOf(allowed), keys, future, skipped);
    }

    /** 派生格式、受众、重置、事件引用和切分边界须与当前可恢复的原文一致。 */
    private static boolean usable(State state, State empty, Map<String, Line> keys) {
        if (state == null || state.format() != 1 || !empty.conversation().equals(state.conversation())
                || !empty.reset().equals(state.reset()) || state.items() == null || state.closedProof() == null
                || state.covered() == null || state.refs() == null || !keys.keySet().containsAll(state.closedProof())) return false;
        if (state.covered().isEmpty())
            return state.offset() == 0 && state.items().isEmpty() && state.closedProof().isEmpty() && state.refs().isEmpty();
        var cover = keys.get(state.covered());
        if (cover == null || state.offset() < 0 || state.offset() > cover.text().length()
                || state.offset() < cover.text().length() && Character.isLowSurrogate(cover.text().charAt(state.offset()))) return false;
        var pinned = new LinkedHashMap<String, Long>();
        for (var item : state.items()) {
            if (item == null || item.kind() == null || !Set.of("TOPIC","CONSTRAINT","OPEN_POINT").contains(item.kind())
                    || item.text() == null || item.text().isBlank() || item.origins() == null || item.origins().isEmpty()
                    || !keys.keySet().containsAll(item.origins())) return false;
            for (var origin : item.origins()) ChatHistory.mergeRefs(pinned, keys.get(origin).refs());
        }
        for (var origin : state.closedProof()) ChatHistory.mergeRefs(pinned, keys.get(origin).refs());
        return pinned.equals(state.refs());
    }

    /** 返回本轮的唯一提要投影。未参与的 QQ 路径不会进入此方法。 */
    DialogueHistory prepare(Trace trace, ChatWorker worker, MemoryCall memory) throws IOException {
        int budget = worker.historyBudget();
        String session = trace.scope().sessionId();
        long startTime = System.nanoTime();
        var frozen = freeze(trace, memory);
        Head head;
        try { head = store.read(session); }
        catch (IOException failure) {
            failure.printStackTrace(System.err);
            var original = view(trace, frozen, new Head(0, null), memory);
            var fallback = render(trace, original, memory, budget);
            report(trace, "FAILED", failure.getMessage(), null, original, fallback, "", startTime, Map.of());
            return fallback.history();
        }
        var view = view(trace, frozen, head, memory);
        var state = view.state();
        // 重置、撤权、丢源及版本降级永久作废该派生版本。后来一轮的提交不能被早到的请求覆盖。
        if (!view.future() && head.revision() > 0 && !state.equals(head.state())) {
            try {
                synchronized (audit) {
                    if (sameReset(trace, state.reset()) && store.commit(session, head.revision(), state))
                        head = new Head(head.revision() + 1, state);
                    else head = store.read(session);
                }
            } catch (IOException failure) {
                failure.printStackTrace(System.err);
                var fallback = render(trace, view, memory, budget);
                report(trace, "FAILED", failure.getMessage(), head, view, fallback, "", startTime, Map.of());
                return fallback.history();
            }
            view = view(trace, freeze(trace, memory), head, memory);
            state = view.state();
        }
        var lines = view.lines();
        int begin = ChatSummary.firstUncovered(state, lines);
        long units = uncovered(state, lines, begin) + state.rendered().length();
        int recent = budget / 2 - budget / 12;
        int tail = lines.size();
        while (tail > begin && lines.get(tail - 1).text().length() <= recent) {
            var last = lines.get(tail - 1);
            if (partial(state, last)) break;
            recent -= last.text().length();
            tail--;
        }
        if (tail == lines.size() && tail > begin && !partial(state, lines.getLast())
                && (long) lines.getLast().text().length() + state.rendered().length() <= budget) tail--;
        if (view.future() || tail <= begin || (units < 3 * budget / 4 && !state.backlog() && state.rendered().length() <= budget / 12)
                || !preparing.add(session)) return render(trace, view, memory, budget).history();

        String status = "REJECTED", reason = "", first = "";
        Map<String, Integer> usage = Map.of();
        try {
            var batch = ChatSummary.batch(state, lines, begin, tail, budget);
            if (batch == null) throw new ChatSummary.Rejected("编号和旧条目已占满单批输入预算");
            first = batch.start();
            // 压缩模型本身是交付边界。复核服务不可用时沿原调用的失败语义退出。
            if (!sameReset(trace, state.reset()) || !authorized(batch.dependencies(), versions(memory, batch.dependencies().keySet())))
                throw new ChatSummary.Rejected("冻结来源或重置点已变更");
            ChatWorker.Summary answer = null;
            try { answer = worker.summarize(batch.input(), budget / 12); }
            catch (ChatWorker.InvalidOutput failure) {
                usage = failure.usage();
                throw new ChatSummary.Rejected(failure.getMessage(), failure);
            }
            catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                failure.printStackTrace(System.err);
                status = "FAILED";
                reason = failure.getMessage();
            }
            if (answer != null) {
                usage = answer.usage();
                var next = ChatSummary.validate(answer.text(), batch, state, view.byKey(), budget / 12, stream(memory));
                if (!authorized(batch.dependencies(), versions(memory, batch.dependencies().keySet())))
                    throw new ChatSummary.Rejected("候选依据的引用权或版本已变更");
                synchronized (audit) {
                    if (!sameReset(trace, state.reset())) throw new ChatSummary.Rejected("冻结期间会话已重置");
                    try {
                        if (!store.commit(session, head.revision(), next)) throw new ChatSummary.Rejected("会话修订已前移");
                        head = new Head(head.revision() + 1, next);
                        status = "COMMITTED";
                    } catch (IOException failure) {
                        failure.printStackTrace(System.err);
                        status = "FAILED";
                        reason = failure.getMessage();
                    }
                }
            }
        } catch (ChatSummary.Rejected failure) {
            reason = failure.getMessage();
            if (failure.getCause() != null) failure.printStackTrace(System.err);
        } catch (IOException failure) {
            // 此处的读取是授权边界，服务失联不能将未经复核的历史降级交给普通模型。
            report(trace, "FAILED", failure.getMessage(), head, view,
                    render(trace, view, memory, budget), first, startTime, usage);
            throw failure;
        }
        finally { preparing.remove(session); }
        // 提交结果不确定或有并发候选时以实际持久修订对账，随后按原请求截点重新验证投影。
        try { head = store.read(session); }
        catch (IOException failure) { failure.printStackTrace(System.err); status = "FAILED"; reason = failure.getMessage(); }
        view = view(trace, freeze(trace, memory), head, memory);
        var result = render(trace, view, memory, budget);
        report(trace, status, reason, head, view, result, first, startTime, usage);
        return result.history();
    }

    /** QQ 的 AMBIENT 判断只读取最近完整原文；读取授权与将来的规划一致，此处不会读取或生成提要。 */
    DialogueHistory recent(Trace trace, MemoryCall memory) throws IOException {
        return render(trace, view(trace, freeze(trace, memory), new Head(0, null), memory), memory, 48_000, false).history();
    }

    private boolean sameReset(Trace trace, String expected) {
        return audit.latestReset(trace.scope()).equals(expected);
    }

    private static boolean partial(State state, Line line) {
        return line.key().equals(state.covered()) && state.offset() < line.text().length();
    }

    private static long uncovered(State state, List<Line> lines, int begin) {
        long total = 0;
        for (int i = begin; i < lines.size(); i++) {
            var line = lines.get(i);
            total += line.text().length() - (partial(state, line) ? state.offset() : 0);
        }
        return total;
    }

    private Rendered render(Trace trace, View view, MemoryCall memory, int budget) throws IOException {
        // 缩小模型档位时保留原提交及其未决项，直到有条件重新整理。本轮按新预算重取可核验完整原文。
        if (view.state().rendered().length() > budget / 12)
            view = view(trace, freeze(trace, memory), new Head(0, null), memory);
        return render(trace, view, memory, budget, true);
    }

    /** 最近期消息整条装入。未纳入的中间片段只以短说明计入同一个硬上限。 */
    private Rendered render(Trace trace, View view, MemoryCall memory, int budget, boolean markGap) {
        var state = view.state();
        var lines = view.lines();
        int begin = ChatSummary.firstUncovered(state, lines);
        String summary = state.rendered();
        long total = uncovered(state, lines, begin);
        boolean missing = total + summary.length() > budget || begin < lines.size() && partial(state, lines.get(begin));
        int room = budget - summary.length() - (missing && markGap ? GAP.length() : 0);
        int tail = lines.size(), retained = 0;
        while (tail > begin) {
            var line = lines.get(tail - 1);
            if (partial(state, line) || line.text().length() > room) break;
            room -= line.text().length();
            retained += line.text().length();
            tail--;
        }
        var messages = new ArrayList<DialogueMessage>();
        if (!summary.isEmpty()) messages.add(new DialogueMessage(Speaker.NOTE, summary));
        long gap = total - retained;
        if (gap > 0 && markGap) messages.add(new DialogueMessage(Speaker.NOTE, GAP));
        var refs = new LinkedHashMap<>(state.refs());
        for (var line : lines.subList(tail, lines.size())) {
            messages.add(line.message());
            ChatHistory.mergeRefs(refs, line.refs());
        }
        var projection = new DialogueHistory(messages, refs, () -> {
            if (!authorized(refs, versions(memory, refs.keySet())) || !sameReset(trace, state.reset()))
                throw new IOException("会话历史依据已变更，本次交付中止");
        });
        return new Rendered(projection, gap, lines.size() - tail);
    }

    /** 常规审计只含位置、单位数、耗时、实际用量及提要指纹，不复写模型的私有正文。 */
    private void report(Trace trace, String status, String reason, Head head, View view, Rendered result,
                        String start, long nanos, Map<String, Integer> usage) throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("status", status);
        data.put("reason", reason);
        data.put("headKnown", head != null);
        if (head != null) {
            data.put("revision", head.revision());
            data.put("covered", head.state() == null ? "" : head.state().covered());
            data.put("offset", head.state() == null ? 0 : head.state().offset());
        }
        data.put("start", start);
        data.put("summaryUnits", view.state().rendered().length());
        data.put("projectedUnits", result.history().messages().stream().mapToInt(m -> m.text().length()).sum());
        data.put("retainedMessages", result.rawCount());
        data.put("gapUnits", result.gap());
        data.put("skippedSources", view.skipped());
        data.put("latencyMs", (System.nanoTime() - nanos) / 1_000_000);
        data.put("usage", usage);
        try { data.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(view.state().rendered().getBytes(StandardCharsets.UTF_8)))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        trace.write(trace.request(), "chat-worker", "chat_context_compaction", data);
    }

    @Override public void close() throws IOException { store.close(); }
}
