package neuralcloud.controlplane.app;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Node;
import neuralcloud.controlplane.console.ConsoleServer.Reply;
import neuralcloud.controlplane.console.NodeMonitor;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.ReplySelector.Context;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 网页控制台入口。启动顺序：校验全部配置，打开审计链，组装 Chat Worker、Router 与 Master，
 * 启动节点状态读取，最后开放 HTTP 端口。端口开放后才报告就绪；任一阶段失败都释放已打开的资源并退出。
 */
public final class ConsoleMain {
    private static final ConfigSource DEFAULT_CONFIG = ConfigSource.bundled("console.properties");

    private ConsoleMain() {
    }

    // 测试场景入口通过它追加节点。组件已按控制台配置组装；正式入口不追加任何节点。
    @FunctionalInterface
    interface Extension {
        List<Node> nodes(Components components) throws IOException;
    }

    record Components(ChatWorker chat, Router<Route> router, Master master, Master.Settings routerSettings,
                      Master.Settings masterSettings) {
    }

    public static void main(String[] args) {
        exitOnFailure(run(Main.configSource(args, DEFAULT_CONFIG), components -> List.of()));
    }

    static void exitOnFailure(int code) {
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(ConfigSource config, Extension extension) {
        ConsoleConfig.Settings settings;
        ChatWorkerConfig.Settings chatSettings;
        Master.Settings routerSettings;
        Master.Settings masterSettings;
        String chatKey;
        String chatPrompt;
        String routerPrompt;
        try {
            settings = ConsoleConfig.load(config);
            chatSettings = ChatWorkerConfig.load(settings.chatWorkerConfig());
            chatKey = chatSettings.apiKey();
            chatPrompt = settings.chatWorkerRolePrompt().readText();
            routerSettings = MasterConfig.load(settings.routerConfig());
            routerPrompt = settings.routerRolePrompt().readText();
            masterSettings = MasterConfig.load(settings.masterConfig());
            if (!routerSettings.usesChat()) {
                throw new IllegalArgumentException("Router 需要支持 tool_calls 的 /v1/chat/completions 端点");
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            var audit = AuditLog.open(settings.auditDirectory());
            opened.push(audit);
            var chatClient = chatSettings.newClient();
            opened.push(chatClient);
            var routerClient = HttpClient.newBuilder().connectTimeout(routerSettings.connectTimeout()).build();
            opened.push(routerClient);
            var masterClient = HttpClient.newBuilder().connectTimeout(masterSettings.connectTimeout()).build();
            opened.push(masterClient);
            var metricsClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(settings.metricsTimeout()).build();
            opened.push(metricsClient);

            var chat = chatSettings.create(chatClient, chatKey, chatPrompt);
            var routerInference = new Master(routerClient, routerSettings);
            var router = new Router<>(routerInference, routerPrompt, Route.class);
            var master = new Master(masterClient, masterSettings);
            var nodes = new ArrayList<>(List.of(
                    new Node("chat-worker", "Chat Worker", chatSettings.endpoint().toString(), null,
                            (text, trace) -> new Reply("chat_worker_reply",
                                    Map.of("text", chat.reply(Context.direct(text), text).orElseThrow())), null),
                    new Node("router", "Router", routerSettings.endpoint().toString(), routerSettings.model(),
                            (text, trace) -> new Reply("router_route", Map.of("route", router.route(text).name())),
                            routerInference::probeStartup),
                    new Node("master", "Master", masterSettings.endpoint().toString(), masterSettings.model(),
                            (text, trace) -> {
                                var completion = master.complete(text, settings.masterMaxTokens(),
                                        settings.masterTemperature(), List.of());
                                return new Reply("master_completion", Map.of("text", completion.text(),
                                        "finishReason", completion.finishReason(),
                                        "reasoningFieldPresent", completion.reasoningFieldPresent()));
                            },
                            master::probeStartup)));
            nodes.addAll(extension.nodes(new Components(chat, router, master, routerSettings, masterSettings)));

            var monitor = new NodeMonitor(metricsClient, settings.metricsTimeout(), settings.gpuMetrics(),
                    Map.of("router", settings.routerMetrics(), "master", settings.masterMetrics()));
            opened.push(monitor);
            monitor.start(settings.metricsInterval());

            var server = ConsoleServer.start(settings.listen(), audit, monitor, nodes);
            opened.push(server);
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("控制台启动失败");
            error.printStackTrace(System.err);
            closeAll(opened);
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAll(opened), "console-shutdown"));
        var address = settings.listen();
        System.out.println("控制台已就绪：http://" + address.getHostString() + ":" + address.getPort() + "/");
        return 0;
    }

    // 后打开的先关闭：先停止接收请求，再停止状态读取、关闭模型客户端，最后关闭审计文件。
    private static void closeAll(Deque<AutoCloseable> opened) {
        while (!opened.isEmpty()) {
            try {
                opened.pop().close();
            } catch (Exception error) {
                System.err.println("释放控制台资源失败");
                error.printStackTrace(System.err);
            }
        }
    }
}
