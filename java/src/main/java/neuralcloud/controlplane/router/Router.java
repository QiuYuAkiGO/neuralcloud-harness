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

/**
 * 基于受限函数调用（Function Calling）的泛型请求路由器。
 * 职责：根据调用方提供的流程枚举 {@code R} 动态生成严格 JSON Schema 的 {@code select_route} 工具定义，
 * 强制小模型在 temperature=0 下通过工具参数提交唯一合法的路由枚举值，杜绝自由文本解析带来的漂移。
 *
 * @param <R> 允许分流的目标业务流程枚举类型
 */
public final class Router<R extends Enum<R>> {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    /** 固定工具名：所有路由模型必须调用且仅调用此函数返回分类结果。 */
    private static final String ROUTE_TOOL = "select_route";
    private final Master inference;
    private final String rolePrompt;
    private final Class<R> routeType;
    /** 预构造的 OpenAI tools 声明，枚举候选集合直接取自 {@code routeType} 的枚举常量。 */
    private final List<Map<String, Object>> tools;

    /**
     * 构造路由器实例。
     * 调用方提供流程枚举，工具 Schema 约束与结果反序列化共用同一份契约，确保枚举变更自动同步。
     */
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

    /**
     * 对给定的待分类报告或用户消息进行路由判定。
     * 强制要求模型执行恰好一次 {@code select_route} 调用，且参数 JSON 只包含合法的 {@code route} 字符串字段；
     * 否则视为违反路由契约并抛出 {@link IOException}。
     */
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

}
