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
        if (refresh != null && !refresh.updates().isEmpty())
            memory.v2().finishRefresh(inputId + "-refresh", refresh);
    }

    public void delivered() throws IOException {
        if (memory.v2() != null && !bindings.isEmpty()) memory.v2().delivered(inputId);
    }

    public void verifyEvidence() throws IOException, InterruptedException {
        if (memory.v2() == null) return;
        var current = new java.util.ArrayList<>(bindings);
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
        String description = "只读查询。HISTORY 查当前会话的历史材料，SELF_IDENTITY 查当前用户已确认的称呼。"
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
