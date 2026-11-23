package neuralcloud.controlplane.app;

import neuralcloud.controlplane.channel.OpenQqChannel;
import neuralcloud.controlplane.channel.QqGroups;
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
import neuralcloud.controlplane.memory.ObserveEvidence;
import neuralcloud.controlplane.memory.ObserveQueue;
import neuralcloud.controlplane.orchestration.Orchestrator;
import neuralcloud.controlplane.orchestration.ObserveScheduler;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.ReplySelector;
import neuralcloud.controlplane.worker.TaskWorker;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 网页控制台与全链路控制面的主启动入口。
 * 启动顺序严格分阶段：
 * 1. 配置校验：统一加载控制台、Chat Worker、Router、Master 及可选的 Task Worker、OpenQQ、Memory 配置，并检查跨组件契约；
 * 2. 基础设施就绪：打开审计目录、连接并核验记忆服务（含 v2 本地 SQLite 日志恢复）；
 * 3. 节点装配：创建各模型 HTTP 客户端、对话处理器（{@link #conversation} 与 {@link #masterConversation}）及监控轮询器；
 * 4. 开放入口：启动本机 HTTP 控制台服务，随后按需启动 OpenQQ WebSocket 渠道；
 * 5. 注册优雅关闭钩子：所有已打开资源压栈登记，启动失败或进程退出时按后进先出（LIFO）逆序释放。
 */
public final class ConsoleMain {
    private static final ConfigSource DEFAULT_CONFIG = ConfigSource.bundled("console.properties");

    private ConsoleMain() {
    }

    /** 测试场景扩展点：允许测试入口在已装配的正式组件基础上追加临时节点；正式入口始终返回空列表。 */
    @FunctionalInterface
    interface Extension {
        List<Node> nodes(Components components) throws IOException;
    }

    /** 传递已装配核心节点的轻量容器，供测试场景扩展点复用真实推理客户端。 */
    record Components(ChatWorker chat, Master routerInference, Master master, Master.Settings routerSettings,
                      Master.Settings masterSettings) {
    }

    /** 记忆读写权限分离包装：模型节点只持有按轮次绑定的读取入口 {@code reads}，持久写入统一归口给 {@code writes}。 */
    record MemoryAccess(
            ConversationMemory reads,
            RouterMemoryWriter writes,
            Optional<ObservationAccess> observations) {}

    /** 仅注册到动态群控制面，模型拿到的材料中不含调度和写入句柄。 */
    record ObservationAccess(ObserveQueue queue, ObserveEvidence evidence) {}

    public static void main(String[] args) {
        exitOnFailure(run(Main.configSource(args, DEFAULT_CONFIG), components -> List.of()));
    }

    /** 非零退出码表示启动失败，显式终止 JVM；成功返回 0 时保留 HTTP 与后台线程持续运行。 */
    static void exitOnFailure(int code) {
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * 按既定顺序完成全部配置加载、依赖核验与服务启动。
     * 退出码约定：0 表示就绪运行；2 表示配置或跨模块契约校验失败；1 表示运行时打开资源或外部核验失败。
     */
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
        // 第一阶段：加载并校验全部配置与跨模块前置条件，任一配置非法直接退出，不创建任何网络或磁盘资源。
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
            // 项目群聊涉及跨受众数据隔离，必须开启 Memory v2 的 scoped 模式以防私聊记忆泄漏到群内。
            if (openQqSettings
                            .filter(
                                    q ->
                                            q.allowedGroupId() != 0
                                                    || q.groupPoliciesFile().isPresent())
                            .isPresent()
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

        // 第二阶段：依次打开持久化、记忆、推理客户端、监控、HTTP 控制台与 OpenQQ，并通过栈记录确保逆序清理。
        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            var audit = AuditLog.open(settings.auditDirectory());
            opened.push(audit);
            var memory =
                    openMemory(
                            memorySettings,
                            opened,
                            audit,
                            openQqSettings.isPresent(),
                            openQqSettings
                                    .flatMap(OpenQqConfig.Settings::groupPoliciesFile)
                                    .isPresent());
            var chatClient = chatSettings.newClient();
            opened.push(chatClient);
            var routerClient = routerSettings.newClient();
            opened.push(routerClient);
            var masterClient = masterSettings.newClient();
            opened.push(masterClient);
            var metricsClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(settings.metricsTimeout()).build();
            opened.push(metricsClient);

            // 组装各节点及其统一对话入口，注册到网页控制台节点列表。
            var chat = chatSettings.create(chatClient, chatKey, chatPrompt);
            var task = taskSettings.map(configured -> {
                var taskClient = configured.newClient();
                opened.push(taskClient);
                return configured.create(taskClient);
            });
            var routerInference = new Master(routerClient, routerSettings);
            var router = new Router<>(routerInference, routerPrompt, Route.class);
            var master = new Master(masterClient, masterSettings);
            var foreground = new ObserveScheduler.Foreground();
            var chatExchange = foreground.wrap(conversation(chat, router, task, memory));
            var nodes =
                    new ArrayList<>(
                            List.of(
                                    new Node(
                                            "chat-worker",
                                            "Chat Worker",
                                            chatSettings.endpoint().toString(),
                                            chatSettings.model(),
                                            chatExchange,
                                            null),
                                    new Node(
                                            "router",
                                            "Router",
                                            routerSettings.endpoint().toString(),
                                            routerSettings.model(),
                                            foreground.wrap(
                                                    (text, trace) ->
                                                            new Reply(
                                                                    "router_route",
                                                                    Map.of(
                                                                            "route",
                                                                            router.route(text)
                                                                                    .name()))),
                                            routerInference::probeStartup),
                                    new Node(
                                            "master",
                                            "Master",
                                            masterSettings.endpoint().toString(),
                                            masterSettings.model(),
                                            foreground.wrap(
                                                    masterConversation(
                                                            master,
                                                            settings.masterMaxTokens(),
                                                            settings.masterTemperature(),
                                                            memory.map(MemoryAccess::reads))),
                                            () ->
                                                    foreground.run(
                                                            () -> {
                                                                master.probeStartup();
                                                                return null;
                                                            }))));
            // 只有已握手的 Memory v2 能把意图证据解析为具体版本，才开放正式的对话派单入口。
            memory.filter(store -> store.writes().supportsAttestation()).ifPresent(store ->
                    nodes.add(new Node("conversation", "对话编排", routerSettings.endpoint().toString(), routerSettings.model(),
                            foreground.wrap(plannedConversation(master, router, chat, store, settings.masterMaxTokens(), settings.masterTemperature())), null)));
            nodes.addAll(extension.nodes(new Components(chat, routerInference, master, routerSettings, masterSettings)));

            // 仅对已配置 Prometheus /metrics 端点的节点注册指标抓取，随后启动后台周期监控。
            var nodeMetrics = new LinkedHashMap<String, NodeMonitor.Metrics>();
            settings.routerMetrics().ifPresent(source -> nodeMetrics.put("router", source));
            settings.masterMetrics().ifPresent(source -> nodeMetrics.put("master", source));
            var monitor = new NodeMonitor(metricsClient, settings.metricsTimeout(), settings.gpuMetrics(), nodeMetrics);
            opened.push(monitor);
            monitor.start(settings.metricsInterval());

            var writes = memory.map(MemoryAccess::writes);
            Optional<QqGroups> groups;
            if (openQqSettings.flatMap(OpenQqConfig.Settings::groupPoliciesFile).isPresent()) {
                var qq = openQqSettings.orElseThrow();
                var store = memory.orElseThrow();
                var observation = store.observations().orElseThrow();
                var queue = observation.queue();
                var roster =
                        new QqGroups(
                                qq.groupPoliciesFile().orElseThrow(), qq.accountId(), audit, queue);
                groups = Optional.of(roster);
                // 恢复启用水位和未决命令后先启动调度；必须等 OneBot 名单核验后才能取证或首发。
                var scheduler =
                        new ObserveScheduler(
                                qq.accountId(),
                                queue,
                                roster,
                                observation.evidence(),
                                store.writes(),
                                audit,
                                master,
                                foreground,
                                settings.masterMaxTokens(),
                                settings.masterTemperature());
                opened.push(scheduler);
                scheduler.start();
            } else groups = Optional.empty();
            var server =
                    ConsoleServer.start(settings.listen(), audit, monitor, nodes, writes, groups);
            opened.push(server);

            // 网页控制台与 OpenQQ 渠道共用同一对话处理器 chatExchange。QQ 登录验证在后台连接中完成，断开时可独立重连。
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
                                        writes,
                                        groups,
                                        (text, trace, mode, addressed) ->
                                                foreground.run(() -> managedQq(
                                                        text,
                                                        trace,
                                                        addressed,
                                                        memory.orElseThrow(),
                                                        master,
                                                        router,
                                                        chat,
                                                        task,
                                                        settings.masterMaxTokens(),
                                                        settings.masterTemperature())));
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

    /**
     * 打开并核验常驻记忆服务客户端。
     * v2 模式下会在审计目录下挂载角色专属 SQLite 持久日志 {@link MemoryJournal}，并在 {@code verify} 期间
     * 核对版本、角色、必需能力列表以及回放未落盘的写入队列；只有核验完全通过才返回读写封装。
     */
    static Optional<MemoryAccess> openMemory(Optional<MemoryConfig> settings,
                                                     Deque<AutoCloseable> opened, AuditLog audit, boolean qqPrivateEnabled)
            throws IOException, InterruptedException {
        return openMemory(settings, opened, audit, qqPrivateEnabled, false);
    }

    /** 群策略管理依赖服务端的群通知受众契约，握手不通过时不会开放渠道。 */
    private static Optional<MemoryAccess> openMemory(
            Optional<MemoryConfig> settings,
            Deque<AutoCloseable> opened,
            AuditLog audit,
            boolean qqPrivateEnabled,
            boolean managedGroups)
            throws IOException, InterruptedException {
        if (settings.isEmpty()) {
            return Optional.empty();
        }
        var config = settings.orElseThrow();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        opened.push(client);
        ResidentMemory memory;
        if (config.apiVersion() == 2) {
            // v2 依赖本地 SQLite 日志登记会话句柄、写入回执与授权状态，实现崩溃恢复。
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
        // 核验远端健康状态与能力契约；v2 还会在此触发本地待确认写入记录的补偿恢复。
        if (memory instanceof ResidentV2 v2) v2.verify(qqPrivateEnabled, managedGroups);
        else memory.verify(qqPrivateEnabled);
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
        var observation =
                memory instanceof ResidentV2 v2 && managedGroups
                        ? Optional.of(
                                new ObservationAccess(
                                        v2.observationQueue(), new ObserveEvidence(v2)))
                        : Optional.<ObservationAccess>empty();
        return Optional.of(
                new MemoryAccess(
                        new ConversationMemory(audit, memory),
                        new RouterMemoryWriter(audit, memory),
                        observation));
    }

    /**
     * 构造 Master 节点的对话处理函数。
     * 配置记忆时为本轮绑定一个 {@link MemoryCall} 句柄，允许 Master 自主发起记忆工具查询并将结果写入审计链；
     * 未配置记忆时直接走无工具补全。
     */
    static ConsoleServer.Exchange masterConversation(Master master, int maxTokens, double temperature,
                                                     Optional<ConversationMemory> memory) {
        return (text, trace) -> {
            // 操作者在 Master 节点请求一份结构化意图。事件保存宿主解析后的证据版本，供后续派单引用。
            if (text.equals("/intent") || text.startsWith("/intent ")) {
                String prompt = text.substring("/intent".length()).stripLeading();
                if (prompt.isBlank()) throw new IOException("/intent 需要非空事项正文");
                var read = memory.flatMap(store -> store.bindMasterIntent(trace, trace.request()))
                        .orElseThrow(() -> new IOException("submit_intent 需要配置 Memory v2 以核对证据版本"));
                var intent = master.submitIntent(prompt, maxTokens, temperature, read);
                return new Reply("intent", intent.data(), read.eventId());
            }
            var read = memory.map(store -> store.bind(trace, Caller.MASTER, trace.request()));
            var completion = read.isPresent() ? master.completeWithMemory(text, maxTokens, temperature, read.orElseThrow())
                    : master.complete(text, maxTokens, temperature, List.of());
            return new Reply("master_completion", Map.of("text", completion.text(), "finishReason", completion.finishReason(),
                    "reasoningFieldPresent", completion.reasoningFieldPresent()), read.map(MemoryCall::eventId).orElse(trace.request()));
        };
    }

    /** 控制台的自然语言入口。先持久化原话、分流，再交 Master 查证；只有日常对话进入此单步计划。 */
    static ConsoleServer.Exchange plannedConversation(Master master, Router<Route> router, ChatWorker chat,
                                                       MemoryAccess store, int maxTokens, double temperature) {
        return (text, trace) -> {
            int inbound = store.writes().rememberInbound(trace, trace.request(), text);
            var route = router.route(text);
            if (route != Route.CONVERSATION) {
                return new Reply("router_route", Map.of("route", route.name()), inbound);
            }
            int routed =
                    trace.write(inbound, "router", "router_route", Map.of("route", route.name()));
            return plannedReply(
                    text,
                    trace,
                    routed,
                    master,
                    router,
                    chat,
                    store,
                    maxTokens,
                    temperature,
                    false);
        };
    }

    /** 接收策略允许参与时，以前台优先级入库、判断和派单。观察群由渠道的原文接收线程处理。 */
    private static Optional<Reply> managedQq(
            String text,
            ConsoleServer.Trace trace,
            boolean addressed,
            MemoryAccess store,
            Master master,
            Router<Route> router,
            ChatWorker chat,
            Optional<TaskWorker> task,
            int maxTokens,
            double temperature)
            throws IOException, InterruptedException {
        int input = store.writes().rememberInbound(trace, trace.request(), text);
        var context =
                addressed
                        ? ReplySelector.Context.direct(text)
                        : new ReplySelector.Context(
                                ReplySelector.Addressing.AMBIENT,
                                text,
                                trace.dialogue().stream().map(m -> m.text()).toList());
        var decision = chat.selectReply(context);
        int selected =
                trace.write(
                        input,
                        "chat-worker",
                        "qq_participation",
                        Map.of("addressing", context.addressing(), "decision", decision));
        if (decision == ReplySelector.Decision.SKIP) return Optional.empty();
        var route = router.route(text);
        int routed = trace.write(selected, "router", "router_route", Map.of("route", route.name()));
        if (route == Route.CONVERSATION)
            return Optional.of(
                    plannedReply(
                            text,
                            trace,
                            routed,
                            master,
                            router,
                            chat,
                            store,
                            maxTokens,
                            temperature,
                            true));
        var conversation =
                new ChatWorker.RoutedConversation(ReplySelector.Context.direct(text), route);
        if (route == Route.ACTION_REQUEST && task.isPresent())
            return Optional.of(
                    executeTask(chat, task.get(), conversation, trace, routed, Optional.of(store)));
        return Optional.of(
                new Reply(
                        "chat_worker_reply",
                        Map.of("text", chat.reply(conversation).orElseThrow()),
                        routed));
    }

    /** 从已入账的同一条原文继续 Master 验收和 Router 单步派单，发送前复核依据的版本。 */
    private static Reply plannedReply(
            String text,
            ConsoleServer.Trace trace,
            int routed,
            Master master,
            Router<Route> router,
            ChatWorker chat,
            MemoryAccess store,
            int maxTokens,
            double temperature,
            boolean replyRequired)
            throws IOException, InterruptedException {
            var read = store.reads().bindMasterIntent(trace, routed).orElseThrow();
        var intent = master.submitIntent(text, maxTokens, temperature, read, replyRequired);
            int accepted = trace.write(read.eventId(), "master", "intent", intent.data());
            var scope = new Orchestrator.Scope(trace.scope().conversationId(), UUID.randomUUID());
            var submission = router.submitPlan(intent, scope);
            if (submission.isEmpty()) return new Reply("conversation_no_reply", Map.of(), accepted);

            var submitted = submission.orElseThrow();
            var plan = submitted.plan();
            int planned = trace.write(accepted, "router", "router_plan", Map.of("tool", "submit_plan",
                    "toolCallId", submitted.toolCallId(), "workflowId", scope.workflowId(), "steps", plan.steps()));
            // Router 生成计划与角色撰写回复期间，证据都可能发生变化。派发及交给渠道前分别复核。
            read.verifyEvidence();
        var receipts =
                new Orchestrator()
                        .<Reply>execute(
                                plan,
                                (bound, step, dependencies) -> {
                                    int dispatched =
                                            trace.write(
                                                    planned,
                                                    "orchestrator",
                                                    "worker_dispatch",
                                                    Map.of(
                                                            "workflowId",
                                                            bound.workflowId(),
                                                            "action",
                                                            step.id(),
                                                            "worker",
                                                            step.worker(),
                                                            "text",
                                                            step.text()));
                                    String reply =
                                            chat.replyToPlan(step.text(), intent.replyIntent());
                                    if (intent.evidence().stream()
                                                    .anyMatch(read.noticeEvidence()::containsKey)
                                            && !intent.quotes().stream().allMatch(reply::contains))
                                        throw new IOException("Chat Worker 回复缺少获准群通知的原文片段");
                                    return new Orchestrator.Receipt<>(
                                            bound,
                                            step.id(),
                                            new Reply(
                                                    "chat_worker_reply",
                                                    Map.of(
                                                            "workflowId",
                                                            bound.workflowId(),
                                                            "stepId",
                                                            step.id(),
                                                            "text",
                                                            reply),
                                                    dispatched));
                                });
            read.verifyEvidence();
            return receipts.get(plan.steps().getFirst().id()).result();
    }

    /**
     * 构造前台自由对话主链路（供控制台 Chat Worker 页签和 OpenQQ 渠道共用）。
     * 有记忆时的标准执行时序：
     * 1. 先落盘入站用户发言（{@code rememberInbound}）——若持久写入失败则立即中止，避免模型基于未落盘事实作答；
     * 2. 拉取本轮增量更新/热目录卡片（{@code startTurn}），交由 Chat Worker 进行轮次规划（{@code planTurn}）；
     * 3. 根据规划结果分流：直接回复/澄清（{@code REPLY/CLARIFY}）、交 Router 分流执行现场动作（{@code ACTION}）、
     *    或携带按需记忆工具查询后再生成角色回复（{@code HISTORY/SELF_IDENTITY/SELF_HISTORY}）。
     */
    static ConsoleServer.Exchange conversation(ChatWorker chat, Router<Route> router, Optional<TaskWorker> task,
                                                Optional<MemoryAccess> memory) {
        return (text, trace) -> {
            if (memory.isEmpty()) {
                return routeAction(chat, router, task, text, trace, trace.request(), Optional.empty());
            }
            int accepted = trace.write(trace.request(), "chat-worker", "chat_worker_received", Map.of());
            var store = memory.orElseThrow();
            // 先由 Router 写入口持久化用户观察事件，取得审计事件编号作为后续全部步骤的因果父节点。
            int input = store.writes().rememberInbound(trace, accepted, text);
            var chatRead = store.reads().bind(trace, Caller.CHAT_WORKER, input);
            var updates = chatRead.startTurn();
            // 若记忆服务已提供热目录/近期更新包，则不再额外拼接审计链对话文本，避免上下文重复注入。
            var turn =
                    chat.planTurn(
                            text,
                            updates.isEmpty() ? trace.dialogue() : List.of(),
                            store.reads().canSearchSelfHistory(trace.scope()),
                            updates);
            // 规划阶段已消费过增量变更通知，推进本地游标避免下一轮重复注入同一批更新。
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

    /**
     * 将本轮用户消息交给 Router 判定业务流程：
     * 仅当 Router 明确路由到 {@link Route#ACTION_REQUEST} 且系统配置了任务 Worker 时才派发现场执行；
     * 否则退回由 Chat Worker 按路由结果直接生成角色回复。
     */
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

    /**
     * 执行现场动作并遵循“先持久记录意图 -> 执行采样 -> 持久落盘回执 -> 再由角色润色回复”的严格顺序：
     * 1. 执行外部动作前先通过回调登记工具调用请求（{@code rememberTool(..., completed=false)}）；
     * 2. 若任务 Worker 判定当前请求超出已有执行能力（返回空），记录不可用事件并回退给 Chat Worker 礼貌说明；
     * 3. 读取成功后先写审计事件与记忆工具结果回执（{@code completed=true}），最后才交由 Chat Worker 将测量值转述给用户。
     */
    private static Reply executeTask(ChatWorker chat, TaskWorker task, ChatWorker.RoutedConversation conversation,
            ConsoleServer.Trace trace,
            int routed,
            Optional<MemoryAccess> memory)
            throws IOException, InterruptedException {
        int dispatched = trace.write(routed, "task-worker", "task_worker_dispatch",
                Map.of("provider", task.endpoint().getHost(), "model", task.model()));
        var read = memory.map(store -> store.reads().bind(trace, Caller.TASK_WORKER, dispatched));
        // 以“运行批次 ID + 派发审计事件序号”构造稳定的 actionId，将请求与回执配对关联。
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

    /**
     * 按打开的逆序（后进先出）逐个关闭资源：
     * 先停止 OpenQQ 与 HTTP 控制台接收新请求，再停监控与关闭各 HTTP 客户端、SQLite 日志，最后关闭审计文件。
     * 单个资源关闭异常不会阻断其余资源的释放。
     */
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
