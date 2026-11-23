package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import neuralcloud.controlplane.console.ConsoleServer.Trace.Scope;
import neuralcloud.controlplane.memory.MemoryCall.EvidenceRef;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static neuralcloud.controlplane.memory.ResidentV2.integer;
import static neuralcloud.controlplane.memory.ResidentV2.string;

/** Master 的后台只读取证入口。目标来自本批原文回执，受众始终是同一 QQ 群的 CITE 身份。 */
public final class ObserveEvidence {
    private static final int WINDOW = 16384;
    // Memory 单条正文上限为 32768 码点；最大的一条修订原文也能完整占用一个批次。
    static final int TEXT_BUDGET = 32768;
    private final ResidentV2 memory;

    public ObserveEvidence(ResidentV2 memory) {
        this.memory = memory;
    }

    public record Item(EvidenceRef ref, String text, String occurredAt) {}

    public record Packet(List<ObserveQueue.Source> members, List<Item> originals) {
        public List<EvidenceRef> refs() {
            return originals.stream().map(Item::ref).toList();
        }

        /** 给模型的编号只覆盖实际交付的一手原文，已有印象始终在编号表之外。 */
        public String material(List<String> comparison) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var item : originals)
                rows.add(Map.of("编号", rows.size() + 1, "原文", item.text(), "时间", item.occurredAt()));
            return new Gson().toJson(Map.of("本批原文", rows, "已有印象", comparison));
        }
    }

    private ResidentV2.Snapshot window(Scope scope, List<String> ids, int start)
            throws IOException, InterruptedException {
        var read = memory.readWindow(scope, "MASTER", "HISTORY", ids, start, WINDOW);
        if (!memory.stream().equals(read.stream()) || ids.size() != read.items().size())
            throw new IOException("观察群回读的流或条数不符");
        for (int i = 0; i < ids.size(); i++) {
            var item = read.items().get(i);
            String id =
                    item.has("ref")
                            ? string(item.getAsJsonObject("ref"), "record_id")
                            : string(item, "record_id");
            if (!id.equals(ids.get(i))) throw new IOException("观察群回读目标与原文回执不符");
        }
        return read;
    }

    /** 修订可能长于 QQ 入站文本。窗口重叠一个码点，以区分恰好填满的末页和后续正文。 */
    private Item original(Scope scope, String id, JsonObject item)
            throws IOException, InterruptedException {
        String status = string(item, "status");
        if (Set.of("RETRACTED", "SOURCE_CHANGED", "NOT_FOUND").contains(status)) return null;
        if (!"ONLINE".equals(status)
                || !"observation".equals(string(item, "kind"))
                || item.has("grant_dependent") && item.get("grant_dependent").getAsBoolean())
            throw new IOException("观察群原文状态、类型或受众不符");
        var ref = item.getAsJsonObject("ref");
        String chunk = string(item, "text");
        var text = new StringBuilder(chunk);
        for (int offset = WINDOW - 1;
                chunk.codePointCount(0, chunk.length()) == WINDOW;
                offset += WINDOW - 1) {
            var tail = window(scope, List.of(id), offset).items().getFirst();
            if (!"ONLINE".equals(string(tail, "status"))
                    || !ref.equals(tail.getAsJsonObject("ref")))
                throw new IOException("读取完整群原文期间来源发生修订");
            chunk = string(tail, "text");
            text.append(chunk.substring(chunk.offsetByCodePoints(0, 1)));
        }
        return new Item(
                new EvidenceRef(id, integer(ref, "revision", 1)),
                text.toString(),
                string(item, "occurred_at"));
    }

    /** 按入库序号选取完整前缀。通知原文和同批更正可能指向相同记录，只交付最新版本一次。 */
    public Packet read(Scope scope, List<ObserveQueue.Source> sources)
            throws IOException, InterruptedException {
        var ids = sources.stream().map(ObserveQueue.Source::recordId).distinct().toList();
        if (ids.isEmpty()) throw new IOException("观察批次缺少已确认原文");
        var rows = window(scope, ids, 0).items();
        var items = new LinkedHashMap<String, Item>();
        var members = new ArrayList<ObserveQueue.Source>();
        int used = 0;
        for (var source : sources) {
            String id = source.recordId();
            if (!items.containsKey(id)) {
                var item = original(scope, id, rows.get(ids.indexOf(id)));
                if (item != null) {
                    int size = item.text().codePointCount(0, item.text().length());
                    if (used + size > TEXT_BUDGET) break;
                    used += size;
                }
                items.put(id, item);
            }
            members.add(source);
        }
        if (members.isEmpty()) throw new IOException("单条群原文超出 Memory 正文预算");
        return new Packet(
                List.copyOf(members), items.values().stream().filter(Objects::nonNull).toList());
    }

    /** 以本批原文查询相关线索，只交付同群自己的在线印象作对照。 */
    public List<String> comparison(Scope scope, Packet packet, List<String> recent)
            throws IOException, InterruptedException {
        var first = packet.originals().getFirst().text();
        String query =
                first.substring(
                        0,
                        first.offsetByCodePoints(
                                0, Math.min(128, first.codePointCount(0, first.length()))));
        var found =
                memory.lookup(
                        scope,
                        "MASTER",
                        ConversationMemory.QueryKind.HISTORY,
                        query,
                        Instant.now());
        if (!memory.stream().equals(found.stream())) throw new IOException("观察群印象召回属于另一条 Memory 流");
        var rows = new ArrayList<JsonObject>();
        if (!recent.isEmpty()) {
            var previous = memory.readItems(scope, "MASTER", "HISTORY", recent);
            if (!memory.stream().equals(previous.stream()))
                throw new IOException("观察群已有印象属于另一条 Memory 流");
            rows.addAll(previous.items());
        }
        rows.addAll(found.items());
        var result = new LinkedHashMap<String, String>();
        for (var item : rows) {
            if ("ONLINE".equals(string(item, "status"))
                    && "impression".equals(string(item, "kind"))
                    && !item.has("grant_dependent"))
                result.putIfAbsent(
                        string(item.getAsJsonObject("ref"), "record_id"), string(item, "text"));
        }
        return List.copyOf(result.values());
    }
}
