package neuralcloud.controlplane.app;

import neuralcloud.controlplane.console.ConsoleServer.Node;
import neuralcloud.controlplane.console.ConsoleServer.Reply;
import neuralcloud.controlplane.evaluation.MasterEvaluation;
import neuralcloud.controlplane.evaluation.RouterEvaluation;
import neuralcloud.controlplane.evaluation.TraceSink;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Outcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 控制台的会议改期联调入口，只在测试源码中提供。启动顺序与正式控制台相同，另追加“会议改期链路”节点：
 * 操作者正文交给真实 Router 分流，Master 按用例的上报与证据取证并提交意图，编排器派单，Chat Worker 生成回复。
 * 记忆证据来自样例，提醒核对回执按启动参数模拟，每一步都写入控制台审计链。
 */
public final class ConsoleScenarioMain {
    private static final Path RESOURCES = Path.of("java", "src", "test", "resources", "evaluation");
    private static final ConfigSource DEFAULT_CONFIG = ConfigSource.file(RESOURCES.resolve("console-scenario.properties"));
    private static final Path MASTER_ROLE_PROMPT = RESOURCES.resolve("master-role-prompt.txt");
    private static final String USAGE =
            "请使用 --scenario <样例 JSON> <用例 ID> <PRESENT|CANCELLED|FAILED> [--config <控制台配置>]";

    // 挡板事件类型对应的执行节点，审计链按节点显示每一步的归属。
    private static final Map<String, String> EVENT_NODES = Map.ofEntries(
            Map.entry("router_run_started", "router"), Map.entry("router_input", "router"),
            Map.entry("router_route", "router"), Map.entry("router_rejected", "router"),
            Map.entry("router_report", "router"), Map.entry("router_plan", "router"),
            Map.entry("router_run_completed", "router"),
            Map.entry("model_input", "master"), Map.entry("model_output", "master"),
            Map.entry("reasoning_filtered", "master"), Map.entry("query_retry", "master"),
            Map.entry("required_results", "master"), Map.entry("submission_retry", "master"),
            Map.entry("result_submission", "master"), Map.entry("decision", "master"),
            Map.entry("decision_evidence", "master"), Map.entry("case_result", "master"),
            Map.entry("memory_query", "memory"), Map.entry("memory_result", "memory"),
            Map.entry("java_no_data", "memory"),
            Map.entry("worker_dispatch", "orchestrator"),
            Map.entry("chat_worker_draft", "chat-worker"), Map.entry("reply_receipt", "chat-worker"),
            Map.entry("reminder_waiting", "task-worker"), Map.entry("reminder_receipt", "task-worker"));

    private ConsoleScenarioMain() {
    }

    public static void main(String[] args) {
        Path fixtures;
        String caseId;
        Outcome outcome;
        ConfigSource config;
        try {
            if (args.length < 4 || !args[0].equals("--scenario")
                    || Arrays.stream(args, 1, 4).anyMatch(arg -> arg.isBlank() || arg.startsWith("--"))) {
                throw new IllegalArgumentException(USAGE);
            }
            fixtures = Path.of(args[1]);
            caseId = args[2];
            outcome = Outcome.valueOf(args[3]);
            config = Main.configSource(Arrays.copyOfRange(args, 4, args.length), DEFAULT_CONFIG);
        } catch (IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            System.exit(2);
            return;
        }
        ConsoleMain.exitOnFailure(ConsoleMain.run(config,
                components -> List.of(meetingNode(components, fixtures, caseId, outcome))));
    }

    private static Node meetingNode(ConsoleMain.Components components, Path fixtures, String caseId, Outcome outcome)
            throws IOException {
        if (!components.masterSettings().usesChat()) {
            throw new IllegalArgumentException("会议改期链路的 Master 需要支持 tool_calls 的 /v1/chat/completions 端点");
        }
        var evaluation = new MasterEvaluation(components.master(), fixtures, Files.readString(MASTER_ROLE_PROMPT));
        var pipeline = new RouterEvaluation(components.router()::route, evaluation, components.chat()::draftReply);
        pipeline.requireScenario(caseId);
        String routerModel = components.routerSettings().model();
        String masterModel = components.masterSettings().model();
        return new Node("meeting-scenario", "会议改期链路", "Router → Master → 编排器 → Chat Worker",
                caseId + " · 提醒核对模拟为 " + outcome, (text, trace) -> {
                    TraceSink sink = (ignoredCaseId, causedBy, type, data) -> {
                        String node = EVENT_NODES.get(type);
                        if (node == null) {
                            throw new IOException("挡板事件类型 " + type + " 未登记执行节点");
                        }
                        return trace.write(causedBy, node, type, data);
                    };
                    var run = pipeline.run(sink, trace.request(), caseId, text, outcome, routerModel, masterModel);
                    return new Reply("pipeline_completed", Map.of("completed", run.completed(),
                            "replies", run.replies(), "caseId", caseId, "simulatedOutcome", outcome));
                }, null);
    }
}
