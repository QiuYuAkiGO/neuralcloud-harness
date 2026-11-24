package neuralcloud.controlplane.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.ChatHistory.Line;
import neuralcloud.controlplane.worker.DialogueMessage.Speaker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一个冻结批次拥有唯一的模型序号空间。落库前将序号还原为本地事件，模型不提交权限或游标。 */
final class ChatSummary {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final Set<String> KINDS = Set.of("TOPIC", "CONSTRAINT", "OPEN_POINT");

    record Item(String kind, String text, List<String> origins) {}
    record State(int format, String conversation, String reset, String stream, String covered, int offset, boolean backlog,
                 List<Item> items, List<String> closedProof, Map<String, Long> refs) {
        static State empty(String conversation, String reset) {
            return new State(1, conversation, reset, "", "", 0, false, List.of(), List.of(), Map.of());
        }
        String rendered() { return render(items); }
    }
    record Entry(int index, String kind, String text, String speaker, String event, String request, String receipt, String participation,
                 Integer start, Integer end) {}
    record Source(Item previous, Line raw, boolean whole) {
        List<String> origins() { return previous == null ? List.of(raw.key()) : previous.origins(); }
    }
    record Batch(String input, List<Source> sources, String covered, int offset, boolean backlog, String start,
                 Map<String, Long> dependencies) {}

    /** 标签和换行均占用摘要预算。空摘要无需向模型注入数据块。 */
    static String render(List<Item> items) {
        if (items.isEmpty()) return "";
        var text = new StringBuilder("同会话历史提要");
        for (var item : items) {
            String label = switch (item.kind()) {
                case "TOPIC" -> "话题";
                case "CONSTRAINT" -> "约束";
                case "OPEN_POINT" -> "未决";
                default -> throw new IllegalArgumentException("未知提要类别");
            };
            text.append('\n').append(label).append('：').append(item.text());
        }
        return text.toString();
    }

    /** 已提交的 offset 是 covered 事件内已消费的 UTF-16 单元数；只有尚未消费完整才需要接续同一条。 */
    static int firstUncovered(State state, List<Line> lines) {
        if (state.covered().isEmpty()) return 0;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).key().equals(state.covered()))
                return state.offset() < lines.get(i).text().length() ? i : i + 1;
        return 0;
    }

    /** 仅向压缩模型提供最早连续前缀。序列化元数据和 JSON 字符转义均计入单次输入预算。 */
    static Batch batch(State state, List<Line> lines, int begin, int tail, int budget) {
        var entries = new ArrayList<Entry>();
        var sources = new ArrayList<Source>();
        var refs = new LinkedHashMap<>(state.refs());
        int wireUnits = "{\"entries\":[]}".length();
        for (var item : state.items()) {
            var entry = new Entry(entries.size() + 1, item.kind(), item.text(), null, null,
                    item.origins().size() == 1 ? item.origins().getFirst() : null, null, null, null, null);
            wireUnits += JSON.toJson(entry).length() + (entries.isEmpty() ? 0 : 1);
            entries.add(entry);
            sources.add(new Source(item, null, true));
        }
        String covered = "";
        int offset = 0, index = begin;
        while (index < tail) {
            var line = lines.get(index);
            int from = line.key().equals(state.covered()) ? state.offset() : 0;
            int end = line.text().length();
            int room = budget - wireUnits - (entries.isEmpty() ? 0 : 1);
            int number = entries.size() + 1;
            Entry entry = null;
            int size = room + 1;
            if (end - from <= room) {
                entry = fragment(number, line, from, end);
                size = JSON.toJson(entry).length();
            }
            if (size > room) {
                // 先提交本批已处理的整条消息，只有首条本身装不下时才分片。
                if (!covered.isEmpty()) break;
                int low = 0, high = Math.min(end - from, Math.max(0, room));
                while (low < high) {
                    int mid = (low + high + 1) >>> 1;
                    if (JSON.toJson(fragment(number, line, from, from + mid)).length() <= room) low = mid;
                    else high = mid - 1;
                }
                end = from + low;
                if (end > from && end < line.text().length() && Character.isHighSurrogate(line.text().charAt(end - 1))) end--;
                if (end == from) break;
                entry = fragment(number, line, from, end);
                size = JSON.toJson(entry).length();
            }
            wireUnits += size + (entries.isEmpty() ? 0 : 1);
            entries.add(entry);
            sources.add(new Source(null, line, from == 0 && end == line.text().length()));
            ChatHistory.mergeRefs(refs, line.refs());
            covered = line.key();
            offset = end;
            if (end < line.text().length()) break;
            index++;
        }
        if (covered.isEmpty()) return null;
        return new Batch(JSON.toJson(Map.of("entries", entries)), List.copyOf(sources), covered, offset,
                index < tail, lines.get(begin).key(), Map.copyOf(refs));
    }

    private static Entry fragment(int index, Line line, int from, int to) {
        return new Entry(index, line.kind().name(), line.text().substring(from, to), line.speaker(),
                line.key(), line.request(), line.receipt(), line.participation(), from, to);
    }

    static final class Rejected extends Exception {
        Rejected(String message) { super(message); }
        Rejected(String message, Throwable cause) { super(message, cause); }
    }

    /** 只按被编号的输入解释回包。所有未决条目须被继承或有一条可追溯的新结项依据。 */
    static State validate(String candidate, Batch batch, State old, Map<String, Line> lines, int limit, String stream) throws Rejected {
        var result = new ArrayList<Item>();
        var inherited = new LinkedHashSet<Integer>();
        var closed = new LinkedHashSet<Integer>();
        var proof = new LinkedHashSet<>(old.closedProof());
        JsonObject root;
        try { root = JSON.fromJson(candidate, JsonObject.class); }
        catch (JsonParseException e) { throw new Rejected("候选 JSON 不合法", e); }
        if (root == null || !root.keySet().equals(Set.of("items", "closed"))
                || !(root.get("items") instanceof JsonArray items) || !(root.get("closed") instanceof JsonArray closures))
            throw invalid();
        for (var row : items) {
            if (!(row instanceof JsonObject item) || !item.keySet().equals(Set.of("kind", "text", "sources"))
                    || !(item.get("kind") instanceof JsonPrimitive k) || !k.isString() || !KINDS.contains(k.getAsString())
                    || !(item.get("text") instanceof JsonPrimitive t) || !t.isString() || t.getAsString().isBlank()
                    || !(item.get("sources") instanceof JsonArray refs) || refs.isEmpty()) throw invalid();
            var origins = new LinkedHashSet<String>();
            boolean supportsOpen = false;
            for (var field : refs) {
                int n = number(field, batch.sources().size());
                var source = batch.sources().get(n - 1);
                origins.addAll(source.origins());
                supportsOpen |= source.previous() != null ? source.previous().kind().equals("OPEN_POINT")
                        : source.raw().kind() == Speaker.USER && !"SKIP".equals(source.raw().participation());
                if (k.getAsString().equals("OPEN_POINT")) inherited.add(n);
            }
            if (k.getAsString().equals("OPEN_POINT") && !supportsOpen) throw invalid();
            result.add(new Item(k.getAsString(), t.getAsString(), List.copyOf(origins)));
        }
        for (var row : closures) {
            if (!(row instanceof JsonObject closure) || !closure.keySet().equals(Set.of("previous", "evidence"))) throw invalid();
            int previous = number(closure.get("previous"), old.items().size());
            int evidence = number(closure.get("evidence"), batch.sources().size());
            var item = old.items().get(previous - 1);
            var cited = batch.sources().get(evidence - 1);
            var line = cited.raw();
            if (!item.kind().equals("OPEN_POINT") || !closed.add(previous) || inherited.contains(previous)
                    || line == null || !cited.whole() || !canClose(item, line, old, lines)) throw invalid();
            proof.addAll(item.origins());
            proof.add(line.key());
        }
        for (int i = 0; i < old.items().size(); i++)
            if (old.items().get(i).kind().equals("OPEN_POINT") && !inherited.contains(i + 1) && !closed.contains(i + 1))
                throw new Rejected("上版未决缺少继承或有效结项依据");
        if (render(result).length() > limit) throw new Rejected("候选提要超出预算");
        var refs = new LinkedHashMap<String, Long>();
        for (var item : result) for (var key : item.origins()) ChatHistory.mergeRefs(refs, lines.get(key).refs());
        for (var key : proof) ChatHistory.mergeRefs(refs, lines.get(key).refs());
        return new State(1, old.conversation(), old.reset(), refs.isEmpty() ? "" : stream, batch.covered(), batch.offset(), batch.backlog(),
                List.copyOf(result), List.copyOf(proof), Map.copyOf(refs));
    }

    /** 成功业务回执须沿同一请求产生。用户取消要求须能指向原请求，未知群成员不能代他人结项。 */
    private static boolean canClose(Item previous, Line evidence, State old, Map<String, Line> lines) {
        var requests = previous.origins().stream().map(lines::get).filter(line -> line.kind() == Speaker.USER).toList();
        if (evidence.kind() == Speaker.MISTY)
            return evidence.receipt() != null && requests.size() == 1 && requests.getFirst().key().equals(evidence.request());
        String cancellation = evidence.text().strip();
        if (cancellation.matches("(?s).*(?:[？?]|别取消|不(?:要|用)?取消|仍然需要|还是需要|继续执行).*")
                || evidence.speaker().equals("群内一位成员")) return false;
        String clause = cancellation.split("[，,。！!；;\\n]", 2)[0].strip();
        boolean unique = old.items().stream().filter(i -> i.kind().equals("OPEN_POINT")).count() == 1;
        for (var origin : requests) {
            if (!origin.speaker().equals(evidence.speaker())) continue;
            if (unique && clause.matches("(?:请)?(?:取消(?:我)?(?:刚才|之前|上次)?(?:的)?(?:那|这)(?:个|项|件)?(?:请求|任务|事)|不用(?:再)?(?:办|做|查)(?:了)?)"))
                return true;
            String subject = origin.text().strip().replaceFirst("^(?:请|麻烦)?(?:你)?(?:帮我)?", "").replaceFirst("[。！!]+$", "");
            if (!subject.isBlank() && (clause.equals("取消" + subject) || clause.equals("不用再" + subject + "了"))) return true;
        }
        return false;
    }

    private static Rejected invalid() { return new Rejected("候选字段、来源编号或结项依据不符合契约"); }

    private static int number(JsonElement field, int max) throws Rejected {
        if (!(field instanceof JsonPrimitive p) || !p.isNumber() || !p.getAsString().matches("[1-9][0-9]{0,8}")) throw invalid();
        int value = p.getAsInt();
        if (value > max) throw invalid();
        return value;
    }
}
