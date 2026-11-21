package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;

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
        this.rolePrompt = rolePrompt;
        this.routeType = routeType;
        var routes = Arrays.stream(routeType.getEnumConstants()).map(Enum::name).toList();
        tools = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", ROUTE_TOOL, "description", "选择当前事项的业务流程",
                        "parameters", Map.of("type", "object", "additionalProperties", false,
                                "required", List.of("route"), "properties", Map.of("route", Map.of(
                                        "type", "string", "enum", routes))))));
    }

    public R route(String report) throws IOException, InterruptedException {
        // 模型只选择流程。会话标识和执行数据由 Java 保留，正文只含事项材料。
        var response = inference.chat(List.of(
                Map.of("role", "system", "content", rolePrompt),
                Map.of("role", "user", "content", report)), tools, 96, 0);
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
}
