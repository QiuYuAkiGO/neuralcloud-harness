package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterIntent;
import neuralcloud.controlplane.orchestration.Orchestrator;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通过原生工具调用进行流程分类，并将已验收的回复意图拆成对话计划。
 * 职责：根据调用方提供的流程枚举 {@code R} 动态生成严格 JSON Schema 的 {@code select_route} 工具定义，
 * 要求模型在 temperature=0 下通过工具参数提交唯一的路由枚举值。
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

    /** 回带模型工具调用标识，审计可从实际派单追到产生它的那次推理。 */
    public record Submission(Orchestrator.Plan plan, String toolCallId) {}

    /** 仅把已批准的回复意图交给 Router 模型；会话、证据及执行权限由宿主持有。 */
    public Optional<Submission> submitPlan(MasterIntent intent, Orchestrator.Scope scope) throws IOException, InterruptedException {
        if (!intent.needsReply()) return Optional.empty();
        var response = inference.chat(List.of(
                Map.of("role", "system", "content", """
                        你是派单 Router。输入是 Master 已批准的回复意图。调用一次 submit_plan，生成一个步骤。
                        id 是步骤名，worker 填 CHAT_WORKER，dependsOn 填空数组。text 用中文指示 Chat Worker 如何表达该意图，
                        保留其中的对象、事实、时间及不确定性。称呼、自我介绍和最终措辞由 Chat Worker 按自己的角色设定决定。
                        不给它指定其他身份，不代写台词或新增行动。"""),
                Map.of("role", "user", "content", intent.replyIntent())), List.of(ChatPlan.TOOL), 512, 0, ChatPlan.NAME);
        var calls = response.calls();
        if ("tool_calls".equals(response.content().finishReason()) && calls.size() == 1
                && ChatPlan.NAME.equals(calls.getFirst().name())) {
            var call = calls.getFirst();
            return Optional.of(new Submission(ChatPlan.parse(call.arguments(), scope), call.id()));
        }
        throw new IOException("Router 须完整提交一次 submit_plan 工具调用");
    }
}
