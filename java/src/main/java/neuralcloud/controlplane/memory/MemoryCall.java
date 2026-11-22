package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 单次节点处理所持的只读接口。调用方、会话和本人身份由渠道和宿主绑定，模型只提交 kind 与 query。 */
public final class MemoryCall {
    public static final String NAME = "memory_call";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern GENERIC_HISTORY = Pattern.compile(
            "\\s*(?:上下文|(?:上文|前文)(?:内容)?|(?:刚才|之前|此前|上次|以上)(?:的)?(?:内容|对话|聊天|话题|信息)"
                    + "|历史(?:记录|对话|内容)|聊天记录)[\\p{P}\\s]*");

    public enum Caller {
        CHAT_WORKER("chat-worker"), TASK_WORKER("task-worker"), MASTER("master");

        private final String node;

        Caller(String node) {
            this.node = node;
        }
    }

    public record Result(QueryKind kind, int count, List<String> sentences) {}

    private final ConversationMemory memory;
    private final Trace trace;
    private final Caller caller;
    private int eventId;
    private final String inputId = java.util.UUID.randomUUID().toString();
    private List<MemoryJournal.Read> bindings = List.of();
    private ResidentV2.Refresh refresh;

    private List<MemoryJournal.Read> hotBindings = List.of();
    private JsonObject previousImpression;

    @FunctionalInterface
    public interface TokenCounter {
        int count(String block) throws IOException, InterruptedException;
    }

    /**
     * The exact string counted below is the only persistent card block supplied to this Master
     * turn.
     */
    public String hotIndex(TokenCounter tokenizer) throws IOException, InterruptedException {
        var v = memory.v2();
        if (caller != Caller.MASTER || v == null || v.hotIndexTokens == 0) return "";
        var cards = v.cardPointers(trace.scope());
        var selected = new ArrayList<JsonObject>();
        String block = "";
        int total = 0;
        for (var entry : cards) {
            var card = entry.getAsJsonObject();
            var fields = new ArrayList<String>();
            fields.add("OPEN".equals(ResidentV2.string(card, "priority")) ? "未决" : "目录");
            fields.add("SUBJECT".equals(ResidentV2.string(card, "axis")) ? "事项" : "人物");
            fields.add(ResidentV2.string(card, "title"));
            if (card.has("deadline")) fields.add("期限 " + ResidentV2.string(card, "deadline"));
            if (card.has("valid_until")) fields.add("有效至 " + ResidentV2.string(card, "valid_until"));
            String line = JSON.toJson(fields);
            String proposal = (block.isEmpty() ? "历史卡目录" : block) + "\n" + line;
            int count;
            try {
                count = tokenizer.count(proposal + "\n\n");
            } catch (IOException unavailable) {
                trace.write(
                        eventId,
                        "memory",
                        "memory_hot_index_unavailable",
                        Map.of("reason", unavailable.toString()));
                return "";
            }
            if (count <= v.hotIndexTokens) {
                selected.add(card);
                block = proposal;
                total = count;
            }
        }
        if (selected.isEmpty()) return "";
        block += "\n\n";
        var ids =
                selected.stream()
                        .map(c -> c.getAsJsonObject("ref").get("record_id").getAsString())
                        .distinct()
                        .toList();
        var now = v.readItems(trace.scope(), caller.name(), "HISTORY", ids);
        var watches = new LinkedHashMap<String, MemoryJournal.Read>();
        for (var item : now.items()) {
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))
                    || (item.has("assertion_active")
                            && !item.get("assertion_active").getAsBoolean())) return "";
            var id = item.getAsJsonObject("ref");
            if (selected.stream().noneMatch(c -> c.get("ref").equals(id))) return "";
            var read =
                    ResidentV2.binding(
                            trace.scope(), caller.name(), "HISTORY", item, now, inputId + "-hot");
            watches.put(read.id(), read);
        }
        // Directory may have been rebuilt/revoked while the remote tokenizer was counting.
        if (!v.cardPointers(trace.scope()).asList().containsAll(selected)) return "";
        hotBindings = List.copyOf(watches.values());
        v.prepareHot(inputId + "-hot", trace.scope(), block, hotBindings);
        verifyDeliverySources(hotBindings);
        eventId =
                trace.write(
                        eventId,
                        "memory",
                        "memory_hot_index",
                        Map.of(
                                "tokens",
                                total,
                                "budget",
                                v.hotIndexTokens,
                                "block",
                                block,
                                "input_id",
                                inputId + "-hot"));
        return block;
    }

    // Only a direct console Master command supplies revision IDs. Historical messages cannot
    // initiate
    // a write.
    public String impressionEvidence(String spec) throws IOException, InterruptedException {
        if (memory.v2() == null
                || !memory.v2().scopedRecall()
                || caller != Caller.MASTER
                || !trace.scope().channel().equals("console"))
            throw new IOException("印象修订需要正式控制台 Master 与 scoped Memory v2");
        var refs = new ArrayList<JsonObject>();
        String[] fields = spec.split(" ", -1);
        if (fields.length < 2 || fields.length > 17)
            throw new IOException("需提供旧印象和 1–16 项原始证据 ID@版本");
        for (String field : fields) {
            if (!field.matches("[0-9a-f]{64}@[1-9][0-9]{0,17}"))
                throw new IOException("来源须为记录ID@版本");
            var r =
                    ResidentV2.object(
                            "record_id",
                            field.substring(0, 64),
                            "revision",
                            Long.parseLong(field.substring(65)));
            if (refs.contains(r)) throw new IOException("来源重复");
            refs.add(r);
        }
        var ids = refs.stream().map(r -> r.get("record_id").getAsString()).toList();
        var now = memory.v2().readItems(trace.scope(), caller.name(), "HISTORY", ids);
        var old = now.items().getFirst();
        previousImpression = refs.getFirst();
        if (!previousImpression.equals(old.get("ref"))
                || !("SOURCE_CHANGED".equals(ResidentV2.string(old, "status"))
                        || ("ONLINE".equals(ResidentV2.string(old, "status"))
                                && "impression".equals(ResidentV2.string(old, "kind")))))
            throw new IOException("旧印象已变化");
        var reads = new ArrayList<MemoryJournal.Read>();
        var evidence = new ArrayList<String>();
        for (int i = 1; i < refs.size(); i++) {
            var item = now.items().get(i);
            if (!"ONLINE".equals(ResidentV2.string(item, "status"))
                    || !refs.get(i).equals(item.get("ref"))
                    || !"direct".equals(ResidentV2.string(item, "basis"))
                    || item.has("assertion_active") && !item.get("assertion_active").getAsBoolean())
                throw new IOException("修订须引用仍可见的当前原始证据");
            evidence.add(ResidentV2.render(item, "HISTORY"));
            reads.add(
                    ResidentV2.binding(
                            trace.scope(), caller.name(), "HISTORY", item, now, inputId));
        }
        bindings = List.copyOf(reads);
        memory.v2().prepare(inputId, caller.name(), trace.scope(), evidence, bindings);
        verifyDeliverySources(bindings);
        return JSON.toJson(evidence);
    }

    public String saveImpression(String text) throws IOException, InterruptedException {
        delivered();
        verifyEvidence();
        var rc =
                memory.v2()
                        .replaceImpression(
                                trace.scope(),
                                trace.scope().runId() + ":" + trace.request() + ":impression",
                                previousImpression,
                                bindings.stream()
                                        .map(b -> b.item().getAsJsonObject("ref"))
                                        .toList(),
                                text);
        eventId =
                trace.write(
                        eventId,
                        "memory",
                        "memory_impression_revised",
                        Map.of(
                                "previous",
                                previousImpression,
                                "receipt",
                                rc,
                                "source_refs",
                                bindings.stream().map(b -> b.item().get("ref")).toList()));
        return rc.recordId() + "@" + rc.revision();
    }

    public List<String> startTurn() throws IOException, InterruptedException {
        var v = memory.v2();
        if (v == null) return List.of();
        refresh = v.refresh(trace.scope(), caller.name());
        if (!refresh.messages().isEmpty()) {
            v.prepare(
                    inputId + "-refresh",
                    caller.name(),
                    trace.scope(),
                    refresh.messages(),
                    refresh.evidence());
            verifyDeliverySources(refresh.evidence());
            eventId =
                    trace.write(
                            eventId,
                            "memory",
                            "memory_context_reset",
                            Map.of(
                                    "materials",
                                    refresh.messages(),
                                    "input_id",
                                    inputId + "-refresh"));
        }
        return refresh.messages();
    }

    public void refreshed() throws IOException {
        if (!hotBindings.isEmpty()) memory.v2().delivered(inputId + "-hot");
        if (refresh != null && !refresh.updates().isEmpty())
            memory.v2().finishRefresh(inputId + "-refresh", refresh);
    }

    public void delivered() throws IOException {
        if (memory.v2() != null && !bindings.isEmpty()) memory.v2().delivered(inputId);
    }

    private void verifyDeliverySources(List<MemoryJournal.Read> evidence)
            throws IOException, InterruptedException {
        var shared = evidence.stream().filter(read -> {
            var item = read.item();
            return item.has("evidence_valid_until")
                    || (item.has("assertion") && item.getAsJsonObject("assertion").has("valid_until"))
                    || (item.has("grant_dependent") && item.get("grant_dependent").getAsBoolean());
        }).toList();
        if (shared.isEmpty()) return;
        // 保存实际输入后复核渠道授权和期限，两者都可能在取证与交付之间变化。
        memory.v2().verifyEvidence(trace.scope(), caller.name(), shared);
        for (var read : shared) {
            long revision = ResidentV2.integer(read.item().getAsJsonObject("ref"), "revision", 1);
            trace.sharedSources().merge(read.id(), revision, Math::min);
            var item = read.item();
            if (item.has("evidence_active") && item.get("evidence_active").getAsBoolean())
                trace.validUntil(
                        java.time.Instant.parse(ResidentV2.string(item, "evidence_valid_until")));
            if (item.has("assertion_active")
                    && item.get("assertion_active").getAsBoolean()
                    && item.getAsJsonObject("assertion").has("valid_until"))
                trace.validUntil(
                        java.time.Instant.parse(
                                ResidentV2.string(
                                        item.getAsJsonObject("assertion"), "valid_until")));
        }
    }

    public void verifyEvidence() throws IOException, InterruptedException {
        if (memory.v2() == null) return;
        var current = new ArrayList<>(bindings);
        current.addAll(hotBindings);
        if (refresh != null)
            for (var r : refresh.evidence())
                if ("ONLINE".equals(ResidentV2.string(r.item(), "status"))) current.add(r);
        memory.v2().verifyEvidence(trace.scope(), caller.name(), current);
    }

    MemoryCall(ConversationMemory memory, Trace trace, Caller caller, int parent) {
        this.memory = memory;
        this.trace = trace;
        this.caller = caller;
        this.eventId = parent;
    }

    public int eventId() {
        return eventId;
    }

    public static boolean validQuery(boolean history, String query) {
        return history ? !query.isBlank() && query.codePointCount(0, query.length()) <= 256
                && !GENERIC_HISTORY.matcher(query).matches() : query.isEmpty();
    }

    public Map<String, Object> tool() {
        boolean selfHistory = memory.canSearchSelfHistory(trace.scope());
        var kinds = selfHistory ? List.of(QueryKind.HISTORY, QueryKind.SELF_IDENTITY, QueryKind.SELF_HISTORY)
                : List.of(QueryKind.HISTORY, QueryKind.SELF_IDENTITY);
        String description =
                "只读查询。HISTORY 查可在当前会话引用的历史材料，SELF_IDENTITY 查当前用户已确认的称呼。"
                        + (selfHistory ? "SELF_HISTORY 跨会话查当前 QQ 发件人以往的原话。" : "");
        return Map.of("type", "function", "function", Map.of("name", NAME, "description", description,
                "parameters", Map.of("type", "object", "additionalProperties", false, "required", List.of("kind", "query"),
                        "properties", Map.of("kind", Map.of("type", "string", "enum", kinds), "query", Map.of(
                                "type", "string", "description", "历史查询填写具体话题关键词，最多 256 字。身份查询填空字符串。")))));
    }

    public Result read(QueryKind kind, String query) throws IOException, InterruptedException {
        if (!validQuery(kind != QueryKind.SELF_IDENTITY, query)
                || (kind == QueryKind.SELF_HISTORY && !memory.canSearchSelfHistory(trace.scope()))) {
            throw new IOException("memory_call 的 kind 或 query 不符合当前渠道的查询契约");
        }
        int requested = trace.write(eventId, caller.node, NAME, Map.of("kind", kind.name(), "query", query));
        ConversationMemory.Recall recalled;
        try {
            recalled = memory.retrieve(trace, requested, kind, query, caller.name(), inputId);
        } catch (IOException | InterruptedException error) {
            trace.write(requested, "memory", "memory_call_failed", Map.of("detail", error.toString()));
            throw error;
        }
        var sentences = recalled.messages();
        if (sentences.isEmpty()) {
            sentences = List.of(switch (kind) {
                case HISTORY -> "当前会话中未查到符合条件的历史记录。";
                case SELF_IDENTITY -> "未查到符合条件的记录。";
                case SELF_HISTORY -> "在能核实为当前账号本人发出的 QQ 历史消息中，未查到符合条件的发言。";
            });
        }
        // 模型和审计共用同一份结果。范围说明及近期上下文不计入实际命中数。
        bindings = recalled.bindings();
        if (memory.v2() != null)
            memory.v2().prepare(inputId, caller.name(), trace.scope(), sentences, bindings);
        verifyDeliverySources(bindings);
        var result = new Result(kind, recalled.matchCount(), sentences);
        eventId = trace.write(recalled.eventId(), "memory", "memory_call_result", result);
        return result;
    }

    /** 原生工具的回复使用模型给出的调用 ID，材料以 tool 角色回传。 */
    public void appendToolResult(List<Map<String, Object>> messages, String id, String arguments, String assistantText)
            throws IOException, InterruptedException {
        var input = parseArguments(arguments);
        var result = read(input.kind(), input.query());
        messages.add(Map.of("role", "assistant", "content", assistantText, "tool_calls", List.of(
                Map.of("id", id, "type", "function", "function", Map.of("name", NAME, "arguments", arguments)))));
        messages.add(Map.of("role", "tool", "tool_call_id", id, "content", String.join("\n", result.sentences())));
    }

    private record Input(QueryKind kind, String query) {}

    private static Input parseArguments(String arguments) throws IOException {
        String rule = "memory_call 参数须仅含字符串 kind 与 query，kind 须为支持的查询类型";
        try {
            var input = JSON.fromJson(arguments, JsonObject.class);
            if (input != null && input.size() == 2 && input.get("kind") instanceof JsonPrimitive kind && kind.isString()
                    && input.get("query") instanceof JsonPrimitive query && query.isString()) {
                return new Input(QueryKind.valueOf(kind.getAsString()), query.getAsString());
            }
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException(rule, error);
        }
        throw new IOException(rule);
    }
}
