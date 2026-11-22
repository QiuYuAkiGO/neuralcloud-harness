package neuralcloud.controlplane.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.memory.ConversationMemory;
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
 * 网页控制台的 HTTP 边界。网页把操作者正文直接交给指定节点，节点的实际返回或失败写入审计链后再回给网页。
 * 请求体在这里按契约校验；节点只收到已校验的非空正文。
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

    @FunctionalInterface
    public interface Exchange {
        Reply send(String text, Trace trace) throws IOException, InterruptedException;
    }

    // 绑定本轮会话的审计链。多步节点把中间步骤写在这里；request 是本轮操作者正文的事件 ID。
    public interface Trace {
        record QqPrivate(long accountId, long peerId) {
            public String conversationId() {
                return "c2c:onebot:" + accountId + ":" + peerId;
            }
        }

        record Scope(String channel, String chain, String runId, Optional<QqPrivate> qq) {
            public Scope(String channel, String chain, String runId) {
                this(channel, chain, runId, Optional.empty());
            }

            public String sessionId() {
                return channel + ":" + chain;
            }

            public String conversationId() {
                return qq.map(QqPrivate::conversationId).orElseGet(this::sessionId);
            }
        }

        int request();

        Scope scope();

        List<DialogueMessage> dialogue();

        int write(Integer causedBy, String node, String type, Object data) throws IOException;

        static Trace forChain(AuditLog audit, String channel, String chain, int request) {
            return bound(audit, new Scope(channel, chain, audit.runId()), request);
        }

        static Trace forQqPrivate(AuditLog audit, String chain, int request, long accountId, long peerId) {
            return bound(audit, new Scope("openqq", chain, audit.runId(), Optional.of(new QqPrivate(accountId, peerId))),
                    request);
        }

        private static Trace bound(AuditLog audit, Scope scope, int request) {
            return new Trace() {
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
                public int write(Integer causedBy, String node, String type, Object data) throws IOException {
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

    private record Message(String chain, String text) {
    }

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
    private final Optional<ConversationMemory> memory;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final String probeChain;

    private ConsoleServer(HttpServer server, AuditLog audit, NodeMonitor monitor, List<Node> nodes,
                          Optional<ConversationMemory> memory) {
        this.server = server;
        this.audit = audit;
        this.monitor = monitor;
        this.memory = memory;
        nodes.forEach(node -> this.nodes.put(node.id(), node));
        // 同一进程内的手动探测串成一条审计链，不混入会话。
        probeChain = PROBE_CHAIN_PREFIX + audit.runId();
    }

    public static ConsoleServer start(InetSocketAddress address, AuditLog audit, NodeMonitor monitor,
                                      List<Node> nodes, Optional<ConversationMemory> memory) throws IOException {
        var console = new ConsoleServer(HttpServer.create(address, 0), audit, monitor, nodes, memory);
        console.server.createContext("/", console::handle);
        console.server.setExecutor(console.executor);
        console.server.start();
        return console;
    }

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

    // 先记录操作者正文，再调用节点。调用失败同样写入审计链，并以 502 返回实际失败原因。
    private Response send(Node node, Message message) throws IOException {
        int request = audit.write(message.chain(), null, node.id(), "operator_message", Map.of("text", message.text()));
        var trace = Trace.forChain(audit, "console", message.chain(), request);
        long started = System.nanoTime();
        Reply reply;
        try {
            reply = node.exchange().send(message.text(), trace);
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
        if ("chat_worker_reply".equals(reply.type()) && memory.isPresent()) {
            // HTTP 正文成功写出才记为渠道回执，服务端无法证明浏览器已渲染。
            return new Response(response.status(), response.contentType(), response.body(), () -> {
                int written = trace.write(event, "console", "console_response_written", Map.of());
                memory.orElseThrow().rememberDelivered(trace, written, (String) reply.data().get("text"));
            });
        }
        return response;
    }

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
        if (body != null && body.keySet().equals(Set.of("chain", "text"))
                && body.get("chain") instanceof JsonPrimitive chain && chain.isString()
                && CHAIN.matcher(chain.getAsString()).matches() && !chain.getAsString().startsWith(PROBE_CHAIN_PREFIX)
                && body.get("text") instanceof JsonPrimitive text && text.isString() && !text.getAsString().isBlank()) {
            return new Message(chain.getAsString(), text.getAsString());
        }
        throw new RequestException(400, INVALID_MESSAGE);
    }

    private static void expect(String method, String allowed) throws RequestException {
        if (!method.equals(allowed)) {
            throw new RequestException(405, "该路径只接受 " + allowed);
        }
    }

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
