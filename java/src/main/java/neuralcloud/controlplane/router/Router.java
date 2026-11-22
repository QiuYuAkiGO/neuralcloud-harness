package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Router<R extends Enum<R>> {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String ROUTE_TOOL = "select_route";
    private final Master inference;
    private final String rolePrompt;
    private final Class<R> routeType;
    private final List<Map<String, Object>> tools;

    // 调用方提供流程枚举，工具约束与结果解析共用同一份契约。
    public Router(Master inference, String rolePrompt, Class<R> routeType) {
        this.inference = inference;
        // 配置提示词统一用 LF 送入模型，避免 Windows 与 Linux 打包产生不同的分流结果。
        this.rolePrompt = rolePrompt.replace("\r\n", "\n");
        this.routeType = routeType;
        var routes = Arrays.stream(routeType.getEnumConstants()).map(Enum::name).toList();
        tools = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", ROUTE_TOOL, "description", "选择当前事项的业务流程", "strict", true,
                        "parameters", Map.of("type", "object", "additionalProperties", false,
                                "required", List.of("route"), "properties", Map.of("route", Map.of(
                                        "type", "string", "enum", routes))))));
    }

    public R route(String report) throws IOException, InterruptedException {
        // 分类必须通过唯一指定的工具输出，避免把小模型的正文当作有效路由。
        var response = inference.chat(List.of(
                Map.of("role", "system", "content", rolePrompt),
                Map.of("role", "user", "content", report)), tools, 96, 0, ROUTE_TOOL);
        var calls = response.calls();
        if (calls.size() == 1 && ROUTE_TOOL.equals(calls.getFirst().name())) {
            try {
                var input = JSON.fromJson(calls.getFirst().arguments(), JsonObject.class);
                if (input != null && input.size() == 1 && input.get("route") instanceof JsonPrimitive value
                        && value.isString()) {
                    return Enum.valueOf(routeType, value.getAsString());
                }
            } catch (JsonParseException | IllegalArgumentException error) {
                throw new IOException("Router 的 select_route.route 不符合流程枚举契约", error);
            }
        }
        throw new IOException("Router 须通过一次 select_route 调用提交唯一的 route 字段");
    }

    public ConversationMemory.Recall queryMemory(ConversationMemory memory, Trace trace, int requested,
                                                  QueryKind kind, String query)
            throws IOException, InterruptedException {
        // Router 从渠道绑定的 trace 取得查询范围，Chat Worker 只提交类型和线索。
        int querying = trace.write(requested, "router", "router_memory_query", Map.of("kind", kind.name()));
        var recalled = switch (kind) {
            case HISTORY -> memory.recall(trace, querying, query);
            case SELF_IDENTITY -> memory.identity(trace, querying);
        };
        // 审计和 Worker 使用同一份正文。零命中时保留查询范围，近期对话和状态说明不增加命中数。
        var sentences = recalled.messages();
        if (sentences.isEmpty()) {
            sentences = List.of(kind == QueryKind.HISTORY ? "当前会话中未查到符合条件的历史记录。" : "未查到符合条件的记录。");
        }
        int returned = trace.write(recalled.eventId(), "router", "router_memory_result",
                Map.of("count", recalled.matchCount(), "sentences", sentences));
        return new ConversationMemory.Recall(sentences, recalled.matchCount(), returned);
    }
}
