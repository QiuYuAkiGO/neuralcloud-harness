package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.evaluation.MasterEvaluation;
import neuralcloud.controlplane.evaluation.RouterEvaluation;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Outcome;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Route;
import neuralcloud.controlplane.router.Router;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class EvaluationMain {
    private static final Path RESOURCES = Path.of("java", "src", "test", "resources", "evaluation");
    private static final Path MASTER_ROLE_PROMPT = RESOURCES.resolve("master-role-prompt.txt");
    private static final Path ROUTER_CONFIG = RESOURCES.resolve("router-qwen35.properties");
    private static final Path ROUTER_ROLE_PROMPT = RESOURCES.resolve("router-role-prompt.txt");
    private static final Path MASTER_CONFIG = RESOURCES.resolve("master-qwen38.properties");

    private EvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        Master.Settings settings;
        Master.Settings routerSettings = null;
        Path fixturesPath;
        Path tracePath;
        String caseId = null;
        Outcome outcome = null;
        try {
            boolean routerEvaluation = args.length > 0 && args[0].equals("--router-eval");
            if (routerEvaluation) {
                if (args.length < 5 || Arrays.stream(args, 1, 5).anyMatch(arg -> arg.isBlank() || arg.startsWith("--"))) {
                    throw new IllegalArgumentException(
                            "请使用 --router-eval <样例 JSON> <用例 ID> <PRESENT|CANCELLED|FAILED> <新的轨迹 JSONL>");
                }
                fixturesPath = Path.of(args[1]);
                caseId = args[2];
                outcome = Outcome.valueOf(args[3]);
                tracePath = Path.of(args[4]);
                args = Arrays.copyOfRange(args, 5, args.length);
                routerSettings = MasterConfig.load(ROUTER_CONFIG);
            } else if (args.length > 0 && args[0].equals("--master-eval")) {
                if (args.length < 3 || args[1].isBlank() || args[1].startsWith("--")
                        || args[2].isBlank() || args[2].startsWith("--")) {
                    throw new IllegalArgumentException("请使用 --master-eval <样例 JSON 路径> <新的轨迹 JSONL 路径>");
                }
                fixturesPath = Path.of(args[1]);
                tracePath = Path.of(args[2]);
                args = Arrays.copyOfRange(args, 3, args.length);
            } else {
                throw new IllegalArgumentException("请指定 --master-eval 或 --router-eval 挡板场景");
            }
            settings = MasterConfig.load(Main.configPath(args, MASTER_CONFIG));
            if (!settings.usesChat() || (routerSettings != null && !routerSettings.usesChat())) {
                throw new IllegalArgumentException("挡板的模型配置需要支持 tool_calls 的 /v1/chat/completions");
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build()) {
            var master = new Master(client, settings);
            var evaluation = new MasterEvaluation(master, fixturesPath, Files.readString(MASTER_ROLE_PROMPT));
            if (routerSettings != null) {
                // 同一联调进程组装两个端点。Router 分流通过后才向 Master 发起本轮取证。
                try (HttpClient routerClient = HttpClient.newBuilder().connectTimeout(routerSettings.connectTimeout()).build()) {
                    var router = new Router<>(new Master(routerClient, routerSettings), Files.readString(ROUTER_ROLE_PROMPT),
                            Route.class);
                    return new RouterEvaluation(router::route, evaluation).run(
                            tracePath, caseId, outcome, routerSettings.model(), settings.model());
                }
            }
            return evaluation.run(tracePath, settings);
        } catch (IOException error) {
            String operation = routerSettings != null ? "Router 挡板" : "Master 挡板";
            String endpoints = routerSettings == null ? settings.endpoint().toString()
                    : "Router " + routerSettings.endpoint() + ", Master " + settings.endpoint();
            System.err.println(operation + "失败 [" + endpoints + "]");
            error.printStackTrace(System.err);
            return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("挡板运行被中断");
            return 1;
        }
    }
}
