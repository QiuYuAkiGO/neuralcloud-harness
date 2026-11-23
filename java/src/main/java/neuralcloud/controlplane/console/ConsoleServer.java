package neuralcloud.controlplane.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.DialogueMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 网页控制台的 HTTP 服务端边界（基于 JDK 内置 {@link HttpServer} 与每请求一虚拟线程执行器）。
 * 职责与核心约定：
 * 1. 提供静态前端页面（{@code /}、{@code /app.js}、{@code /style.css}）以及五类 JSON REST 接口：
 *    节点列表与监控（{@code GET /api/nodes}）、向节点发消息（{@code POST /api/nodes/{id}/messages}）、
 *    手动探测节点（{@code POST /api/nodes/{id}/probe}）、审计链列表与明细（{@code GET /api/audit/chains[/<id>]}）；
 * 2. 在入口边界按严格契约校验 JSON 请求体（最大 64 KiB、拒绝 {@code probe-} 保留链名、限制可选 {@code observation} 白名单键）；
 * 3. 定义跨通道共享的 {@link Trace} 审计追踪与受众范围抽象（控制台、QQ 私聊、QQ 群聊共用），
 *    并在控制台 HTTP 响应正文实际写出之后再通过 {@code afterWrite} 钩子登记 {@code console_response_written} 并触发记忆落盘。
 */
public final class ConsoleServer implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final Gson REQUEST = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern CHAIN = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final String PROBE_CHAIN_PREFIX = "probe-";
    private static final Pattern STATIC = Pattern.compile("/([a-z]+)\\.(html|js|css)");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json; charset=utf-8");
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String INVALID_MESSAGE = "请求体须为只含 chain 与 text 两个字符串字段的 JSON 对象，不超过 64 KiB；"
            + "chain 为 1 至 64 位字母、数字或连字符且不以 probe- 开头，text 非空";

    /** 节点消息处理回调：接收经过边界校验的非空正文及本轮审计追踪 {@link Trace}，返回可审计的回复结构。 */
    @FunctionalInterface
    public interface Exchange {
        Reply send(String text, Trace trace) throws IOException, InterruptedException;
    }

    /**
     * 绑定本轮单次请求的审计与受众隔离上下文（控制台与 OpenQQ 渠道共用）。
     * 负责：记录中间步骤事件、回溯本轮之前的历史对话、收集本轮引用的跨会话共享来源（{@link #sharedSources()}）
     * 及最早有效截止时间（{@link #validUntil()}），供渠道在发送前复核授权并在送达回执中附加 {@code source_refs}。
     */
    public interface Trace {
        /** QQ 私聊渠道身份标识，规范化生成跨重启稳定的会话 ID（{@code c2c:onebot:<accountId>:<peerId>}）。 */
        record QqPrivate(long accountId, long peerId) {
            public String conversationId() {
                return "c2c:onebot:" + accountId + ":" + peerId;
            }
        }

        /** QQ 群聊渠道身份标识（机器人账号、白名单群号、本条消息发件人 QQ 号）。 */
        record QqGroup(long accountId, long groupId, long senderId) {}

        /**
         * 会话隔离作用域：
         * {@code sessionId()} 按渠道与审计链划分；{@code conversationId()} 在 QQ 渠道下映射为跨重启稳定的平台对话 ID，
         * 控制台下与 {@code sessionId()} 保持一致。
         */
        record Scope(
                String channel,
                String chain,
                String runId,
                Optional<QqPrivate> qq,
                Optional<QqGroup> group) {
            public Scope(String channel, String chain, String runId, Optional<QqPrivate> qq) {
                this(channel, chain, runId, qq, Optional.empty());
            }

            public Scope(String channel, String chain, String runId) {
                this(channel, chain, runId, Optional.empty());
            }

            public String sessionId() {
                return channel + ":" + chain;
            }

            public String conversationId() {
                if (group.isPresent())
                    return "group:onebot:" + group.get().accountId() + ":" + group.get().groupId();
                return qq.map(QqPrivate::conversationId).orElseGet(this::sessionId);
            }
        }

        int request();

        Scope scope();

        Map<String, Long> sharedSources();

        void validUntil(java.time.Instant expiry);

        java.time.Instant validUntil();

        /** 为渠道送达回执载荷补充本轮经过复核的跨会话引用清单 {@code source_refs}（若未引用共享来源则原样返回）。 */
        default Map<String, Object> delivery(Map<String, Object> data) {
            if (sharedSources().isEmpty()) return data;
            var receipt = new LinkedHashMap<>(data);
            receipt.put(
                    "source_refs",
                    sharedSources().entrySet().stream()
                            .map(e -> Map.of("record_id", e.getKey(), "revision", e.getValue()))
                            .toList());
            return receipt;
        }

        List<DialogueMessage> dialogue();

        int write(Integer causedBy, String node, String type, Object data) throws IOException;

        static Trace forChain(AuditLog audit, String channel, String chain, int request) {
            return bound(audit, new Scope(channel, chain, audit.runId()), request);
        }

        static Trace forQqPrivate(AuditLog audit, String chain, int request, long accountId, long peerId) {
            return bound(audit, new Scope("openqq", chain, audit.runId(), Optional.of(new QqPrivate(accountId, peerId))),
                    request);
        }

        static Trace forQqGroup(AuditLog audit, String chain, int request, QqGroup group) {
            return bound(
                    audit,
                    new Scope("openqq", chain, audit.runId(), Optional.empty(), Optional.of(group)),
                    request);
        }

        private static Trace bound(AuditLog audit, Scope scope, int request) {
            return new Trace() {
                private final Map<String, Long> shared = new LinkedHashMap<>();
                private java.time.Instant expiry = java.time.Instant.MAX;

                public void validUntil(java.time.Instant bound) {
                    if (bound.isBefore(expiry)) expiry = bound;
                }

                public java.time.Instant validUntil() {
                    return expiry;
                }

                public Map<String, Long> sharedSources() {
                    return shared;
                }

                @Override
                public int request() {
                    return request;
                }

                @Override
                public Scope scope() {
                    return scope;
                }

                @Override
                public List<DialogueMessage> dialogue() {
                    return audit.dialogueBefore(scope.channel(), scope.chain(), request);
                }

                @Override
                public int write(Integer causedBy, String node, String type, Object data)
                        throws IOException {
                    return audit.write(scope.chain(), causedBy, node, type, data);
                }
            };
        }
    }

    @FunctionalInterface
    public interface Probe {
        void run() throws IOException, InterruptedException;
    }

    // type 是写入审计链的事件类型，data 是该节点实际返回的可见内容。
    public record Reply(String type, Map<String, Object> data, Integer causedBy) {
        public Reply(String type, Map<String, Object> data) {
            this(type, data, null);
        }
    }

    // probe 为 null 表示该节点不提供探测，状态以最近一次调用为准。
    public record Node(String id, String name, String endpoint, String model, Exchange exchange, Probe probe) {
    }

    @FunctionalInterface
    private interface AfterWrite {
        void run() throws IOException;
    }

    private record Response(int status, String contentType, byte[] body, AfterWrite afterWrite) {
        Response(int status, String contentType, byte[] body) {
            this(status, contentType, body, null);
        }
    }

    private record Message(String chain, String text, JsonObject observation) {}

    private static final class RequestException extends Exception {
        private final int status;

        RequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AuditLog audit;
    private final NodeMonitor monitor;
    private final Optional<RouterMemoryWriter> writes;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final String probeChain;

    private ConsoleServer(HttpServer server, AuditLog audit, NodeMonitor monitor, List<Node> nodes,
                          Optional<RouterMemoryWriter> writes) {
        this.server = server;
        this.audit = audit;
        this.monitor = monitor;
        this.writes = writes;
        nodes.forEach(node -> this.nodes.put(node.id(), node));
        // 同一进程内的手动探测串成一条审计链，不混入会话。
        probeChain = PROBE_CHAIN_PREFIX + audit.runId();
    }

    /** 绑定监听地址、注册根路由与虚拟线程执行器并立即开始接受 HTTP 请求。 */
    public static ConsoleServer start(InetSocketAddress address, AuditLog audit, NodeMonitor monitor,
                                      List<Node> nodes, Optional<RouterMemoryWriter> writes) throws IOException {
        var console = new ConsoleServer(HttpServer.create(address, 0), audit, monitor, nodes, writes);
        console.server.createContext("/", console::handle);
        console.server.setExecutor(console.executor);
        console.server.start();
        return console;
    }

    /**
     * HTTP 顶层请求分发器：统一捕获客户端契约错误（4xx）与服务端异常（500），
     * 写出响应头（禁用浏览器缓存 {@code no-store}）与响应体，并在响应流关闭确认写出后再执行 {@code afterWrite} 回调。
     */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            Response response;
            try {
                response = route(exchange);
            } catch (RequestException error) {
                response = json(error.status, Map.of("error", error.getMessage()));
            } catch (IOException error) {
                System.err.println("控制台请求处理失败 " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
                error.printStackTrace(System.err);
                response = json(500, Map.of("error", describe(error)));
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            try (var output = exchange.getResponseBody()) {
                output.write(response.body());
            }
            if (response.afterWrite() != null) {
                response.afterWrite().run();
            }
        } finally {
            exchange.close();
        }
    }

    /** 按路径前缀分派静态资源与 {@code /api/...} REST 接口，并强制检查 HTTP 方法。 */
    private Response route(HttpExchange exchange) throws IOException, RequestException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith("/api/")) {
            expect(method, "GET");
            return staticFile(path.equals("/") ? "/index.html" : path);
        }
        String[] parts = path.substring("/api/".length()).split("/", -1);
        if (parts.length == 1 && parts[0].equals("nodes")) {
            expect(method, "GET");
            return json(200, nodesView());
        }
        if (parts.length == 3 && parts[0].equals("nodes") && parts[2].equals("messages")) {
            expect(method, "POST");
            return send(node(parts[1]), message(exchange));
        }
        if (parts.length == 3 && parts[0].equals("nodes") && parts[2].equals("probe")) {
            expect(method, "POST");
            return probe(node(parts[1]));
        }
        if (parts.length == 2 && parts[0].equals("audit") && parts[1].equals("chains")) {
            expect(method, "GET");
            return json(200, Map.of("chains", audit.chains()));
        }
        if (parts.length == 3 && parts[0].equals("audit") && parts[1].equals("chains")) {
            expect(method, "GET");
            var events = CHAIN.matcher(parts[2]).matches() ? audit.events(parts[2]) : List.<AuditLog.Event>of();
            if (events.isEmpty()) {
                throw new RequestException(404, "审计链 " + parts[2] + " 不存在");
            }
            return json(200, Map.of("chain", parts[2], "events", events));
        }
        throw new RequestException(404, "没有接口 " + path);
    }

    /** 汇总全部已注册节点的元数据、监控快照与本机 GPU 读数，供控制台状态栏与右侧监控面板轮询。 */
    private Map<String, Object> nodesView() {
        var views = nodes.values().stream().map(node -> {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", node.id());
            view.put("name", node.name());
            view.put("endpoint", node.endpoint());
            view.put("model", node.model());
            view.put("probe", node.probe() != null);
            view.put("status", monitor.status(node.id()));
            return view;
        }).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("nodes", views);
        body.put("gpu", monitor.gpu());
        return body;
    }

    /**
     * 向指定节点发送操作者消息：
     * 1. 若携带 {@code observation} 来源凭据，仅允许发往启用 Memory v2 的 {@code chat-worker}；
     * 2. 先落盘 {@code operator_message}，调用节点并在返回前执行 {@link RouterMemoryWriter#verifyDelivery} 复核引用权；
     * 3. 记录节点回复与耗时；对于带记忆的 {@code chat_worker_reply}，在 HTTP 响应真正写出后通过 {@code afterWrite}
     *    追加 {@code console_response_written} 并持久化角色回复，避免客户端断开前误记为已送达。
     */
    private Response send(Node node, Message message) throws IOException {
        var observationData = new LinkedHashMap<String, Object>();
        observationData.put("text", message.text());
        if (message.observation() != null) {
            if (!node.id().equals("chat-worker")
                    || writes.isEmpty()
                    || !writes.orElseThrow().supportsAttestation()) {
                return json(400, Map.of("error", "来源凭据只用于启用 Memory v2 的会话入站"));
            }
            observationData.put("observation", message.observation());
        }
        int request =
                audit.write(message.chain(), null, node.id(), "operator_message", observationData);
        var trace = Trace.forChain(audit, "console", message.chain(), request);
        long started = System.nanoTime();
        Reply reply;
        try {
            reply = node.exchange().send(message.text(), trace);
            if (writes.isPresent()) writes.get().verifyDelivery(trace);
        } catch (IOException | InterruptedException error) {
            return failure(node, message.chain(), request, started, error, monitor::recordCall);
        }
        long latency = elapsedMillis(started);
        var data = new LinkedHashMap<>(reply.data());
        data.put("latencyMs", latency);
        int parent = reply.causedBy() == null ? request : reply.causedBy();
        int event = audit.write(message.chain(), parent, node.id(), reply.type(), data);
        monitor.recordCall(node.id(), new NodeMonitor.Outcome(Instant.now().toString(), true, latency, ""));
        var response = json(200, Map.of("eventId", event, "type", reply.type(), "data", data));
        if ("chat_worker_reply".equals(reply.type()) && writes.isPresent()) {
            // HTTP 正文成功写出才记为渠道回执，服务端无法证明浏览器已渲染。
            return new Response(
                    response.status(),
                    response.contentType(),
                    response.body(),
                    () -> {
                        int written =
                                trace.write(
                                        event,
                                        "console",
                                        "console_response_written",
                                        trace.delivery(Map.of()));
                        writes.orElseThrow()
                                .rememberDelivered(
                                        trace, written, (String) reply.data().get("text"));
                    });
        }
        return response;
    }

    /** 对支持探测的节点执行一次同步连通性探测，结果写入专属 {@code probe-<runId>} 审计链并更新监控面板。 */
    private Response probe(Node node) throws IOException, RequestException {
        if (node.probe() == null) {
            throw new RequestException(400, node.name() + " 不提供探测，状态以最近一次调用为准");
        }
        int request = audit.write(probeChain, null, node.id(), "probe_requested", Map.of());
        long started = System.nanoTime();
        try {
            node.probe().run();
        } catch (IOException | InterruptedException error) {
            return failure(node, probeChain, request, started, error, monitor::recordProbe);
        }
        long latency = elapsedMillis(started);
        int event = audit.write(probeChain, request, node.id(), "probe_passed", Map.of("latencyMs", latency));
        monitor.recordProbe(node.id(), new NodeMonitor.Outcome(Instant.now().toString(), true, latency, ""));
        return json(200, Map.of("eventId", event, "latencyMs", latency));
    }

    /** 统一处理节点调用或探测失败：输出完整堆栈、先写 {@code node_failed} 审计事件再恢复中断标记，并以 HTTP 502 返回。 */
    private Response failure(Node node, String chain, int cause, long started, Exception error,
                             BiConsumer<String, NodeMonitor.Outcome> record) throws IOException {
        long latency = elapsedMillis(started);
        String detail = describe(error);
        System.err.println(node.name() + " 调用失败");
        error.printStackTrace(System.err);
        // 审计文件通道会被线程中断关闭，所以先写入失败事件，再恢复中断标记。
        int event = audit.write(chain, cause, node.id(), "node_failed", Map.of("detail", detail, "latencyMs", latency));
        record.accept(node.id(), new NodeMonitor.Outcome(Instant.now().toString(), false, latency, detail));
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return json(502, Map.of("error", detail, "eventId", event));
    }

    private Node node(String id) throws RequestException {
        Node node = nodes.get(id);
        if (node == null) {
            throw new RequestException(404, "节点 " + id + " 不存在，可用节点为 " + nodes.keySet());
        }
        return node;
    }

    /**
     * 解析并校验 {@code POST /api/nodes/{id}/messages} 请求体：
     * 限制体积不超过 64 KiB、严格 JSON、只含 {@code chain} 与 {@code text}（或附带白名单字段的 {@code observation}），
     * 且 {@code chain} 禁止使用系统保留前缀 {@code probe-}；所有格式或契约违规合并由单一 HTTP 400 出口拒绝。
     */
    private static Message message(HttpExchange exchange) throws IOException, RequestException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        JsonObject body = null;
        if (bytes.length <= MAX_BODY_BYTES) {
            try {
                body = REQUEST.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            } catch (JsonParseException error) {
                // 非 JSON 与字段不符由调用方以同样方式修正，共用下方的 400 出口。
                body = null;
            }
        }
        if (body != null
                && (body.keySet().equals(Set.of("chain", "text"))
                        || body.keySet().equals(Set.of("chain", "text", "observation"))
                                && body.get("observation") instanceof JsonObject source
                                && Set.of("speaker", "source_venue", "occurred_at", "assertion",
                                                "topic",
                                                "reply_to")
                                        .containsAll(source.keySet()))
                && body.get("chain") instanceof JsonPrimitive chain && chain.isString()
                && CHAIN.matcher(chain.getAsString()).matches() && !chain.getAsString().startsWith(PROBE_CHAIN_PREFIX)
                && body.get("text") instanceof JsonPrimitive text && text.isString() && !text.getAsString().isBlank()) {
            return new Message(
                    chain.getAsString(), text.getAsString(), body.getAsJsonObject("observation"));
        }
        throw new RequestException(400, INVALID_MESSAGE);
    }

    private static void expect(String method, String allowed) throws RequestException {
        if (!method.equals(allowed)) {
            throw new RequestException(405, "该路径只接受 " + allowed);
        }
    }

    /** 仅放行严格匹配 {@code /[a-z]+.(html|js|css)} 的类路径内置控制台前端静态文件，天然杜绝路径穿越。 */
    private static Response staticFile(String path) throws IOException, RequestException {
        var matcher = STATIC.matcher(path);
        if (matcher.matches()) {
            try (var stream = ConsoleServer.class.getResourceAsStream("/console/" + matcher.group(1) + "." + matcher.group(2))) {
                if (stream != null) {
                    return new Response(200, CONTENT_TYPES.get(matcher.group(2)), stream.readAllBytes());
                }
            }
        }
        throw new RequestException(404, "没有页面 " + path);
    }

    private static Response json(int status, Object body) {
        return new Response(status, CONTENT_TYPES.get("json"), JSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    // 把异常链压成一行给网页和审计链；完整堆栈在边界处输出到标准错误。
    static String describe(Throwable error) {
        var messages = new ArrayList<String>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
            // JDK 包装异常时常沿用下层消息，相邻重复的只保留一条。
            if (messages.isEmpty() || !messages.getLast().equals(message)) {
                messages.add(message);
            }
        }
        return String.join(" ← ", messages);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }
}
