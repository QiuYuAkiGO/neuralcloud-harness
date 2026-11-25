package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.master.MasterIntent;
import neuralcloud.controlplane.orchestration.Orchestrator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private final Optional<ConversationMemory> memoryCall;
    /** 预构造的 OpenAI tools 声明，枚举候选集合直接取自 {@code routeType} 的枚举常量。 */
    private final List<Map<String, Object>> tools;
    private final List<Map<String, Object>> personTools;

    /**
     * 构造路由器实例。
     * 调用方提供流程枚举，工具 Schema 约束与结果反序列化共用同一份契约，确保枚举变更自动同步。
     */
    public Router(Master inference, String rolePrompt, Class<R> routeType) {
        this(inference, rolePrompt, routeType, Optional.empty());
    }

    /** 启动时注册唯一只读处理器。实际消费者在每轮绑定渠道身份，Memory 在读取时裁决引用权限。 */
    public Router(Master inference, String rolePrompt, Class<R> routeType, Optional<ConversationMemory> memoryCall) {
        this.memoryCall = memoryCall;
        this.inference = inference;
        // 配置提示词统一用 LF 送入模型，避免 Windows 与 Linux 打包产生不同的分流结果。
        this.rolePrompt = rolePrompt.replace("\r\n", "\n");
        this.routeType = routeType;
        var routes = Arrays.stream(routeType.getEnumConstants()).map(Enum::name).toList();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("route", Map.of("type", "string", "enum", routes));
        tools = routeTool(fields);
        fields.put("memory_focus", Map.of("type", "string", "enum",
                Arrays.stream(MemoryGatekeeper.Focus.values()).map(Enum::name).toList()));
        fields.put("author_ref", Map.of("type", "string", "maxLength", 256));
        fields.put("query", Map.of("type", "string", "maxLength", 256));
        personTools = routeTool(fields);
    }

    /** 按本轮协商能力选取工具，普通分流保留原来的单字段契约。 */
    private static List<Map<String, Object>> routeTool(Map<String, Object> fields) {
        return List.of(Map.of(
                "type", "function",
                "function", Map.of("name", ROUTE_TOOL, "description", "选择当前事项的业务流程", "strict", true,
                        "parameters", Map.of("type", "object", "additionalProperties", false,
                                "required", List.copyOf(fields.keySet()), "properties", Map.copyOf(fields)))));
    }

    /** 宿主预查和角色主动查证共用已注册的 memory_call，不额外请求一次 Router 模型。 */
    public Optional<ConversationMemory> memoryCall() {
        return memoryCall;
    }

    /** 普通路由仅返回业务流程。 */
    public R route(String report) throws IOException, InterruptedException {
        return select(report, Optional.empty()).route();
    }

    /** 同次强制分流产生业务流程及预查意图。账号、成员权限和时间均不属于模型参数。 */
    public record Selection<R>(R route, MemoryGatekeeper.Intent intent) {}

    /** 有能力的私聊提供上一用户话题（首轮为空串），其余调用方只获得业务路由字段。 */
    public Selection<R> select(String report, Optional<String> previousUserTopic) throws IOException, InterruptedException {
        boolean people = previousUserTopic.isPresent();
        String previous = previousUserTopic.orElse("");
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", people ? rolePrompt + """

                同一次 select_route 还须识别最后一条用户消息的查询对象。按语义填写，不回答用户。
                问某个有名称的人是谁、是否认识、其印象或该人说过什么，选 CONVERSATION 和 PERSON。
                对象有完整称呼但也可能是一般话题的开放评价，选 PERSON_OR_TOPIC。
                这两类的 author_ref 原样摘取对象的完整称呼或 QQ 号，去掉称呼外的引号。
                单问人物时 query 填空串；只在用户明确限定发言话题时填写话题。
                问“哪些人提及某词”时选 MENTION，query 填被提及的原词，author_ref 填空串。
                其他请求（包括形容词用法、问我自己或创造者和现场动作）选 DEFAULT，另两字段均填空串。
                仅当本轮省略对象而明显承接上一用户话题时才从上一话题取称呼。本轮说明的新对象优先。
                """ : rolePrompt));
        if (!previous.isEmpty())
            messages.add(Map.of("role", "user", "content", "上一条用户话题，供省略指代时参考：\n" + previous));
        messages.add(Map.of("role", "user", "content", report));
        var response = inference.chat(messages, people ? personTools : tools, people ? 1024 : 96, 0, ROUTE_TOOL);
        var calls = response.calls();
        try {
            if (calls.size() == 1 && ROUTE_TOOL.equals(calls.getFirst().name())) {
                var in = JSON.fromJson(calls.getFirst().arguments(), JsonObject.class);
                var fields = people ? List.of("route", "memory_focus", "author_ref", "query") : List.of("route");
                if (in != null && in.size() == fields.size()
                        && fields.stream().allMatch(k -> in.get(k) instanceof JsonPrimitive p && p.isString())) {
                    var route = Enum.valueOf(routeType, in.get("route").getAsString());
                    var intent = people ? new MemoryGatekeeper.Intent(
                            MemoryGatekeeper.Focus.valueOf(in.get("memory_focus").getAsString()),
                            in.get("author_ref").getAsString(), in.get("query").getAsString()) : MemoryGatekeeper.Intent.DEFAULT;
                    if (intent.validFor(report, previous)) return new Selection<>(route, intent);
                }
            }
        } catch (JsonParseException | IllegalArgumentException error) {
            throw new IOException(routeRule(people), error);
        }
        throw new IOException(routeRule(people));
    }

    private static String routeRule(boolean people) {
        return people ? "select_route 须一次提交有效 route、memory_focus、author_ref 和 query，称呼须来自用户话题"
                : "Router 须通过一次 select_route 调用提交唯一的 route 字段，符合流程枚举契约";
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
