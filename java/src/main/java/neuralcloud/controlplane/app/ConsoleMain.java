package neuralcloud.controlplane.app;

import neuralcloud.controlplane.channel.OpenQqChannel;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Node;
import neuralcloud.controlplane.console.ConsoleServer.Reply;
import neuralcloud.controlplane.console.NodeMonitor;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.memory.MemoryCall.Caller;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.memory.MemoryJournal;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.TaskWorker;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    record Components(ChatWorker chat, Master routerInference, Master master, Master.Settings routerSettings,
                      Master.Settings masterSettings) {
    }

    record MemoryAccess(ConversationMemory reads, RouterMemoryWriter writes) {}

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
        Optional<TaskWorkerConfig> taskSettings;
        Optional<OpenQqConfig.Settings> openQqSettings;
        Optional<MemoryConfig> memorySettings;
        try {
            settings = ConsoleConfig.load(config);
            chatSettings = ChatWorkerConfig.load(settings.chatWorkerConfig());
            chatKey = chatSettings.apiKey();
            chatPrompt = settings.chatWorkerRolePrompt().readText();
            routerSettings = MasterConfig.load(settings.routerConfig());
            routerPrompt = settings.routerRolePrompt().readText();
            masterSettings = MasterConfig.load(settings.masterConfig());
            taskSettings = settings.taskWorkerConfig().isPresent()
                    ? Optional.of(TaskWorkerConfig.load(settings.taskWorkerConfig().orElseThrow())) : Optional.empty();
            openQqSettings = settings.openQqConfig().isPresent()
                    ? Optional.of(OpenQqConfig.load(settings.openQqConfig().orElseThrow())) : Optional.empty();
            memorySettings = settings.memoryConfig().isPresent()
                    ? Optional.of(MemoryConfig.load(settings.memoryConfig().orElseThrow())) : Optional.empty();
            if (openQqSettings.filter(q -> q.allowedGroupId() != 0).isPresent()
                    && memorySettings
                            .filter(m -> m.apiVersion() == 2 && m.scopedRecall())
                            .isEmpty())
                throw new IllegalArgumentException("启用项目群需要 v2 scoped 会话记忆");
            if (!routerSettings.usesChat()) {
                throw new IllegalArgumentException("Router 需要支持 tool_calls 的 /v1/chat/completions 端点");
            }
            if (memorySettings.isPresent() && !masterSettings.usesChat()) {
                throw new IllegalArgumentException("配置会话记忆时，Master 需要支持 memory_call 的 /v1/chat/completions 端点");
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            var audit = AuditLog.open(settings.auditDirectory());
            opened.push(audit);
            var memory = openMemory(memorySettings, opened, audit, openQqSettings.isPresent());
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
            var task = taskSettings.map(configured -> {
                var taskClient = HttpClient.newBuilder().connectTimeout(configured.connectTimeout()).build();
                opened.push(taskClient);
                return configured.create(taskClient);
            });
            var routerInference = new Master(routerClient, routerSettings);
            var router = new Router<>(routerInference, routerPrompt, Route.class);
            var master = new Master(masterClient, masterSettings);
            var chatExchange = conversation(chat, router, task, memory);
            var nodes = new ArrayList<>(List.of(
                    new Node("chat-worker", "Chat Worker", chatSettings.endpoint().toString(), null,
                            chatExchange, null),
                    new Node("router", "Router", routerSettings.endpoint().toString(), routerSettings.model(),
                            (text, trace) -> new Reply("router_route", Map.of("route", router.route(text).name())),
                            routerInference::probeStartup),
                    new Node("master", "Master", masterSettings.endpoint().toString(), masterSettings.model(),
                            masterConversation(master, settings.masterMaxTokens(), settings.masterTemperature(),
                                    memory.map(MemoryAccess::reads)),
                            master::probeStartup)));
            nodes.addAll(extension.nodes(new Components(chat, routerInference, master, routerSettings, masterSettings)));

            var monitor = new NodeMonitor(metricsClient, settings.metricsTimeout(), settings.gpuMetrics(),
                    Map.of("router", settings.routerMetrics(), "master", settings.masterMetrics()));
            opened.push(monitor);
            monitor.start(settings.metricsInterval());

            var writes = memory.map(MemoryAccess::writes);
            var server = ConsoleServer.start(settings.listen(), audit, monitor, nodes, writes);
            opened.push(server);

            // 两个入口使用同一对话处理器。QQ 登录验证在后台连接中完成，断开时可独立重连。
            openQqSettings.ifPresent(
                    configured -> {
                        var openQqClient = HttpClient.newHttpClient();
                        opened.push(openQqClient);
                        var channel =
                                new OpenQqChannel(
                                        openQqClient,
                                        configured.endpoint(),
                                        configured.accessToken(),
                                        configured.accountId(),
                                        configured.allowedUserId(),
                                        configured.allowedGroupId(),
                                        audit,
                                        chatExchange,
                                        writes);
                        opened.push(channel);
                        channel.start();
                    });
        } catch (IOException | IllegalArgumentException | InterruptedException error) {
            System.err.println("控制台启动失败");
            error.printStackTrace(System.err);
            closeAll(opened);
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeAll(opened), "console-shutdown"));
        var address = settings.listen();
        System.out.println("控制台已就绪：http://" + address.getHostString() + ":" + address.getPort() + "/");
        return 0;
    }

    private static Optional<MemoryAccess> openMemory(Optional<MemoryConfig> settings,
                                                     Deque<AutoCloseable> opened, AuditLog audit, boolean qqPrivateEnabled)
            throws IOException, InterruptedException {
        if (settings.isEmpty()) {
            return Optional.empty();
        }
        var config = settings.orElseThrow();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        opened.push(client);
        ResidentMemory memory;
        if (config.apiVersion() == 2) {
            var journal =
                    new MemoryJournal(
                            audit.directory()
                                    .resolve("memory-v2-" + config.characterId() + ".sqlite"));
            opened.push(journal);
            memory =
                    new ResidentV2(
                            client,
                            config.endpoint(),
                            config.characterId(),
                            config.token(),
                            config.scopedRecall(),
                            journal,
                            config.grantToken(),
                            config.hotIndexTokens(),
                            config.historyProfile());
        } else {
            memory =
                    new ResidentMemory(
                            client,
                            config.endpoint(),
                            config.characterId(),
                            config.token(),
                config.scopedRecall());
        }
        memory.verify(qqPrivateEnabled);
        if (memory instanceof ResidentV2 v2) {
            audit.write(
                    "memory-startup",
                    null,
                    "memory",
                    "memory_ready",
                    Map.of(
                            "characterId",
                            v2.character(),
                            "apiVersion",
                            2,
                            "streamId",
                            v2.stream(),
                            "historyProfile",
                            config.historyProfile()));
        }
        // 两端共用已核验的服务。模型节点只接收按本轮绑定的读取能力，渠道回执只交给 Router 的写入口。
        return Optional.of(new MemoryAccess(new ConversationMemory(audit, memory), new RouterMemoryWriter(audit, memory)));
    }

    static ConsoleServer.Exchange masterConversation(Master master, int maxTokens, double temperature,
                                                     Optional<ConversationMemory> memory) {
        return (text, trace) -> {
            var read = memory.map(store -> store.bind(trace, Caller.MASTER, trace.request()));
            var completion = read.isPresent() ? master.completeWithMemory(text, maxTokens, temperature, read.orElseThrow())
                    : master.complete(text, maxTokens, temperature, List.of());
            return new Reply("master_completion", Map.of("text", completion.text(), "finishReason", completion.finishReason(),
                    "reasoningFieldPresent", completion.reasoningFieldPresent()), read.map(MemoryCall::eventId).orElse(trace.request()));
        };
    }

    static ConsoleServer.Exchange conversation(ChatWorker chat, Router<Route> router, Optional<TaskWorker> task,
                                                Optional<MemoryAccess> memory) {
        return (text, trace) -> {
            if (memory.isEmpty()) {
                return routeAction(chat, router, task, text, trace, trace.request(), Optional.empty());
            }
            int accepted = trace.write(trace.request(), "chat-worker", "chat_worker_received", Map.of());
            var store = memory.orElseThrow();
            int input = store.writes().rememberInbound(trace, accepted, text);
            var chatRead = store.reads().bind(trace, Caller.CHAT_WORKER, input);
            var updates = chatRead.startTurn();
            var turn =
                    chat.planTurn(
                            text,
                            updates.isEmpty() ? trace.dialogue() : List.of(),
                            store.reads().canSearchSelfHistory(trace.scope()),
                            updates);
            chatRead.refreshed();
            int planned = trace.write(input, "chat-worker", "chat_worker_turn_plan",
                    Map.of("kind", turn.kind().name(), "dialogueMessages", turn.dialogue().size(), "usage", turn.usage()));
            return switch (turn.kind()) {
                case REPLY, CLARIFY -> new Reply("chat_worker_reply",
                        Map.of("text", chat.replyToTurn(turn, List.of()).orElseThrow()), planned);
                case ACTION ->
                        routeAction(chat, router, task, text, trace, planned, Optional.of(store));
                case HISTORY, SELF_IDENTITY, SELF_HISTORY -> {
                    var read = store.reads().bind(trace, Caller.CHAT_WORKER, planned);
                    String reply = chat.replyWithMemory(turn, read).orElseThrow();
                    yield new Reply("chat_worker_reply", Map.of("text", reply), read.eventId());
                }
            };
        };
    }

    private static Reply routeAction(ChatWorker chat, Router<Route> router, Optional<TaskWorker> task, String text,
            ConsoleServer.Trace trace,
            int parent,
            Optional<MemoryAccess> memory)
            throws IOException, InterruptedException {
        int submitted = trace.write(parent, "chat-worker", "chat_worker_router_request", Map.of());
        var conversation = chat.routeConversation(text, router);
        int routed = trace.write(submitted, "router", "router_route", Map.of("route", conversation.route().name()));
        if (conversation.route() == Route.ACTION_REQUEST && task.isPresent()) {
            return executeTask(chat, task.orElseThrow(), conversation, trace, routed, memory);
        }
        return new Reply("chat_worker_reply", Map.of("text", chat.reply(conversation).orElseThrow()), routed);
    }

    private static Reply executeTask(ChatWorker chat, TaskWorker task, ChatWorker.RoutedConversation conversation,
            ConsoleServer.Trace trace,
            int routed,
            Optional<MemoryAccess> memory)
            throws IOException, InterruptedException {
        int dispatched = trace.write(routed, "task-worker", "task_worker_dispatch",
                Map.of("provider", "DeepSeek", "model", task.model()));
        var read = memory.map(store -> store.reads().bind(trace, Caller.TASK_WORKER, dispatched));
        String actionId = trace.scope().runId() + ":" + dispatched;
        var status =
                task.execute(
                        conversation.context().message(),
                        read,
                        () -> {
                            if (memory.isPresent())
                                memory.get()
                                        .writes()
                                        .rememberTool(
                                                trace,
                                                read.orElseThrow().eventId(),
                                                actionId,
                                                false,
                                                "已请求读取服务器状态。");
                        });
        int parent = read.map(MemoryCall::eventId).orElse(dispatched);
        if (status.isEmpty()) {
            int receipt = trace.write(parent, "task-worker", "task_worker_unavailable", Map.of());
            return new Reply("chat_worker_reply", Map.of("text", chat.reply(conversation).orElseThrow()), receipt);
        }
        var result = status.orElseThrow();
        // 持久回执后再回复。角色模型润色已完成读取的说明，采样值按回执呈现。
        int receipt = trace.write(parent, "task-worker", "task_worker_result",
                Map.of("capability", "read_server_status", "status", result));
        if (memory.isPresent())
            receipt =
                    memory.get()
                            .writes()
                            .rememberTool(trace, receipt, actionId, true, result.measurements());
        String reply = chat.replyToServerStatus(conversation.context(), result).orElseThrow();
        return new Reply("chat_worker_reply", Map.of("text", reply), receipt);
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
