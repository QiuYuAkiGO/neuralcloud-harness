package neuralcloud.controlplane.channel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Exchange;
import neuralcloud.controlplane.console.ConsoleServer.Reply;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.router.RouterMemoryWriter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenQQ（OneBot v11 正向 WebSocket）渠道适配器。
 * 核心职责与安全边界：
 * 1. 后台虚拟线程维护持久 WebSocket 连接与断线重连（固定 3 秒退避），连接后先调用 {@code get_login_info} 核验登录账号与配置一致；
 * 2. 严格过滤入站事件：仅受理白名单私聊用户、白名单项目群内 @本机器人的纯文本消息，以及启用授权后的原作者私聊 {@code /memory} 控制命令；
 * 3. 基于审计链中的 {@code messageId} / {@code authorMessageId} 做入站幂等去重，防止重连补推触发重复执行；
 * 4. 发送外部回复前通过 {@link RouterMemoryWriter#verifyDelivery} 复核跨会话引用权限；发送超时记 {@code qq_delivery_unknown} 且严禁自动重发，
 *    只有收到 OneBot {@code status=ok, retcode=0} 与有效 {@code message_id} 才确认送达并调用 {@link RouterMemoryWriter#rememberDelivered} 入库。
 */
public final class OpenQqChannel implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    /** WebSocket 建连、发送与等待 OneBot echo 回执的统一超时时间。 */
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(10);
    /** 单帧入站 JSON 事件的最大允许字符数，超限整帧丢弃以防内存放大。 */
    private static final int MAX_EVENT_CHARS = 65536;
    /** 单条有效纯文本消息的最大字符数。 */
    private static final int MAX_MESSAGE_CHARS = 4096;

    /** 通过边界过滤的入站 QQ 消息：{@code groupId == 0} 表示私聊，否则表示白名单项目群内 @机器人的群消息。 */
    record Incoming(long messageId, String text, long groupId, long senderId) {}

    /** 正在等待 OneBot 回执的同步动作请求（通过唯一 {@code echo} 字段与返回帧配对）。 */
    private record Pending(String echo, CompletableFuture<JsonObject> response) {}

    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final long accountId;
    private final long allowedUserId;
    private final long allowedGroupId;
    private final AuditLog audit;
    private final Exchange conversation;
    private final Optional<RouterMemoryWriter> writes;
    private final Thread runner;
    private volatile boolean closed;

    public OpenQqChannel(
            HttpClient client,
            URI endpoint,
            String token,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            AuditLog audit,
            Exchange conversation,
            Optional<RouterMemoryWriter> writes) {
        this.client = client;
        this.endpoint = endpoint;
        this.token = token;
        this.accountId = accountId;
        this.allowedUserId = allowedUserId;
        this.allowedGroupId = allowedGroupId;
        this.audit = audit;
        this.conversation = conversation;
        this.writes = writes;
        this.runner = Thread.ofVirtual().name("openqq-channel").unstarted(this::run);
    }

    /** 启动后台虚拟线程连接 OneBot WebSocket 服务端。 */
    public void start() {
        runner.start();
    }

    /** 渠道主循环：反复建立 {@link Session}，遇网络中断或超时等待 3 秒后自动重连，直到收到关闭信号或线程中断。 */
    private void run() {
        while (!closed) {
            var session = new Session();
            try {
                session.run();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | ExecutionException | TimeoutException error) {
                System.err.println("OpenQQ 连接不可用，将重连：" + error);
            }
            try {
                Thread.sleep(Duration.ofSeconds(3));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 处理单条已通过边界校验的入站 QQ 消息，串行完成：
     * 1. 按渠道与对话对象确定审计链名，拒绝群聊中的 {@code /memory} 授权命令，并按历史审计事件做幂等去重；
     * 2. 写入 {@code qq_message} 或 {@code qq_author_command} 入站事件并构造绑定 QQ 身份的 {@link Trace}；
     * 3. 授权命令直接转交 {@link RouterMemoryWriter#authorDecision}（绝不送入对话模型），普通消息交 {@link Exchange} 并在外发前执行 {@code verifyDelivery} 复核；
     * 4. 通过 OneBot {@code send_private_msg}/{@code send_group_msg} 发送文本；仅在收到含有效 {@code message_id} 的成功回执后才记 {@code qq_delivery} 并落盘角色回复。
     */
    private void handle(Session session, WebSocket socket, Incoming incoming) throws IOException, InterruptedException {
        boolean group = incoming.groupId() != 0;
        String chain =
                group
                        ? "qq-group-" + accountId + "-" + incoming.groupId()
                        : "qq-private-" + accountId + "-" + incoming.senderId();
        boolean decision = authorCommand(incoming.text());
        if (group && decision) return;
        var messageId = new JsonPrimitive(incoming.messageId());
        // 授权命令按渠道消息幂等。若中断在提交后、发送前，重投可取回同一回执。
        if (audit.events(chain).stream()
                .anyMatch(
                        event ->
                                ("qq_message".equals(event.type())
                                                && messageId.equals(
                                                        event.data()
                                                                .getAsJsonObject()
                                                                .get("messageId")))
                                        || ("qq_send_requested".equals(event.type())
                                                && messageId.equals(
                                                        event.data()
                                                                .getAsJsonObject()
                                                                .get("authorMessageId"))))) {
            return;
        }
        int request =
                audit.write(
                        chain,
                        null,
                        "openqq",
                        decision ? "qq_author_command" : "qq_message",
                        Map.of("text", incoming.text(), "messageId", incoming.messageId()));
        var trace =
                group
                        ? Trace.forQqGroup(
                                audit,
                                chain,
                                request,
                                new Trace.QqGroup(
                                        accountId, incoming.groupId(), incoming.senderId()))
                        : Trace.forQqPrivate(audit, chain, request, accountId, incoming.senderId());
        Reply reply;
        try {
            if (decision) {
                var receipt =
                        writes.orElseThrow(() -> new IOException("此渠道未启用原作者授权"))
                                .authorDecision(trace, incoming.messageId(), incoming.text());
                int committed = trace.write(request, "memory", "visibility_decision", receipt);
                String verb = incoming.text().startsWith("/memory grant ") ? "已授予" : "已撤销";
                reply =
                        new Reply(
                                "author_decision_reply",
                                Map.of(
                                        "text",
                                        verb + "授权 " + receipt.get("grant_id").getAsString()),
                                committed);
            } else {
                reply = conversation.send(incoming.text(), trace);
                if (writes.isPresent()) writes.get().verifyDelivery(trace);
            }
        } catch (IOException error) {
            int failed =
                    trace.write(
                            request,
                            decision ? "memory" : "chat-worker",
                            "node_failed",
                            Map.of("detail", error.toString()));
            if (decision)
                reply =
                        new Reply(
                                "author_decision_reply",
                                Map.of("text", "未取得授权决定的回执。" + error.getMessage()),
                                failed);
            else {
                error.printStackTrace(System.err);
                return;
            }
        }
        int drafted = trace.write(reply.causedBy() == null ? request : reply.causedBy(),
                "chat-worker", reply.type(), reply.data());
        int sending =
                trace.write(
                        drafted,
                        "openqq",
                        "qq_send_requested",
                        decision ? Map.of("authorMessageId", incoming.messageId()) : Map.of());
        JsonObject receipt;
        try {
            receipt =
                    session.call(
                            socket,
                            group ? "send_group_msg" : "send_private_msg",
                            Map.of(
                                    group ? "group_id" : "user_id",
                                    group ? incoming.groupId() : incoming.senderId(),
                                    "message",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "data",
                                                    Map.of("text", reply.data().get("text"))))));
        } catch (IOException error) {
            // 发送超时无法区分已提交与未提交，保留不确定回执，禁止自动重发。
            trace.write(sending, "openqq", "qq_delivery_unknown", Map.of("detail", error.getMessage()));
            return;
        }
        if (successful(receipt) && receipt.get("data") instanceof JsonObject data
                && id(data.get("message_id")) > 0) {
            int delivered =
                    trace.write(
                            sending,
                            "openqq",
                            "qq_delivery",
                            trace.delivery(Map.of("messageId", id(data.get("message_id")))));
            if (!decision && writes.isPresent()) {
                writes.orElseThrow().rememberDelivered(trace, delivered, (String) reply.data().get("text"));
            }
        } else if ("failed".equals(string(receipt.get("status")))
                && receipt.get("retcode") instanceof JsonPrimitive code && code.isNumber()) {
            trace.write(sending, "openqq", "qq_delivery_failed", Map.of("retcode", code.getAsNumber()));
        } else {
            trace.write(sending, "openqq", "qq_delivery_unknown", Map.of("detail", "OneBot 回执缺少有效消息 ID"));
        }
    }

    static Incoming incoming(JsonObject event, long accountId, long allowedUserId) {
        return incoming(event, accountId, allowedUserId, 0, false);
    }

    /** 判断消息文本是否为原作者可见性授权控制命令（{@code /memory} 或 {@code /memory ...}）。 */
    private static boolean authorCommand(String text) {
        return text.equals("/memory") || text.startsWith("/memory ");
    }

    /**
     * OneBot v11 入站事件边界过滤器。
     * 过滤规则：
     * - 群聊仅接受配置白名单群（{@code allowedGroupId}）中非机器人自身发送、明确包含 {@code @accountId} 且其余片段全为纯文本的消息；
     * - 私聊仅接受 {@code friend/other} 子类型的纯文本消息；非白名单用户仅在开启原作者授权且正文为 {@code /memory} 命令时放行；
     * - 图片/语音/表情等非纯文本消息或超出 {@value #MAX_MESSAGE_CHARS} 字的正文一律丢弃返回 null。
     */
    static Incoming incoming(
            JsonObject event,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            boolean authorCommands) {
        long sender = id(event.get("user_id"));
        boolean group = "group".equals(string(event.get("message_type")));
        if (group
                ? allowedGroupId == 0
                        || id(event.get("group_id")) != allowedGroupId
                        || sender <= 0
                        || sender == accountId
                : !"private".equals(string(event.get("message_type")))
                        || sender <= 0
                        || sender == accountId
                        || !List.of("friend", "other").contains(string(event.get("sub_type"))))
            return null;
        if (!"message".equals(string(event.get("post_type")))
                || id(event.get("self_id")) != accountId
                || !(event.get("message") instanceof JsonArray segments)) {
            return null;
        }
        long messageId = id(event.get("message_id"));
        var text = new StringBuilder();
        boolean addressed = false;
        for (var item : segments) {
            if (!(item instanceof JsonObject segment)
                    || !(segment.get("data") instanceof JsonObject data)) return null;
            if (group
                    && "at".equals(string(segment.get("type")))
                    && Long.toString(accountId).equals(string(data.get("qq")))) {
                addressed = true;
                continue;
            }
            if (!"text".equals(string(segment.get("type")))
                    || !(data.get("text") instanceof JsonPrimitive part)
                    || !part.isString()) {
                return null;
            }
            text.append(part.getAsString());
        }
        String body = text.toString();
        if (messageId <= 0
                || body.isBlank()
                || body.length() > MAX_MESSAGE_CHARS
                || group && !addressed) return null;
        // 允许原作者发控制命令，无须开放其普通私聊。身份始终取自已认证连接的事件。
        if (!group && sender != allowedUserId && !(authorCommands && authorCommand(body)))
            return null;
        return new Incoming(messageId, body, group ? allowedGroupId : 0, sender);
    }

    /** 判断 OneBot 动作回执是否明确表示执行成功（{@code status == "ok"} 且 {@code retcode == 0}）。 */
    private static boolean successful(JsonObject response) {
        return "ok".equals(string(response.get("status"))) && new JsonPrimitive(0).equals(response.get("retcode"));
    }

    private static String string(JsonElement field) {
        return field instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    private static long id(JsonElement field) {
        if (field instanceof JsonPrimitive value && value.isNumber() && value.getAsString().matches("[1-9][0-9]{0,14}")) {
            return value.getAsLong();
        }
        return 0;
    }

    /** 停止重连循环、中断后台虚拟线程，并在超时范围内等待其优雅退出。 */
    @Override
    public void close() {
        closed = true;
        runner.interrupt();
        try {
            runner.join(IO_TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 单次 WebSocket 连接生命周期封装。
     * WebSocket 回调线程（{@link #onText}）负责拼帧、匹配 {@code pending} 动作回执并将合法入站消息推入有界队列 {@code inbox}；
     * {@link #run()} 在虚拟线程中顺序消费 {@code inbox} 并串行调用 {@link #handle}，保证同一时刻最多只有一条在途动作请求。
     */
    private final class Session implements WebSocket.Listener {
        private final ArrayBlockingQueue<Incoming> inbox = new ArrayBlockingQueue<>(32);
        private final StringBuilder fragments = new StringBuilder();
        private boolean oversized;
        private volatile boolean ended;
        private volatile Pending pending;

        private void run() throws IOException, ExecutionException, TimeoutException, InterruptedException {
            var connection = client.newWebSocketBuilder().connectTimeout(IO_TIMEOUT)
                    .header("Authorization", "Bearer " + token).buildAsync(endpoint, this);
            try {
                var socket = connection.get(IO_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                var login = call(socket, "get_login_info", Map.of());
                if (!successful(login) || !(login.get("data") instanceof JsonObject data)
                        || id(data.get("user_id")) != accountId) {
                    throw new IOException("OpenQQ 登录账号未就绪或与配置不符");
                }
                System.out.println("OpenQQ 私聊接入已就绪");
                while (!closed && !ended) {
                    var message = inbox.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        handle(this, socket, message);
                    }
                }
            } finally {
                connection.thenAccept(WebSocket::abort);
            }
        }

        /** 发送一条带随机 {@code echo} 的 OneBot JSON 动作帧，并阻塞等待对应回执或超时。 */
        private JsonObject call(WebSocket socket, String action, Map<String, ?> params)
                throws IOException, InterruptedException {
            var request = new Pending(UUID.randomUUID().toString(), new CompletableFuture<>());
            pending = request;
            try {
                if (ended) {
                    throw new IOException("OpenQQ 连接已关闭");
                }
                socket.sendText(JSON.toJson(Map.of("action", action, "params", params, "echo", request.echo())), true)
                        .get(IO_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return request.response().get(IO_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException error) {
                throw new IOException("未取得 OneBot 动作回执", error);
            } finally {
                pending = null;
            }
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletableFuture<Void> onText(WebSocket socket, CharSequence data, boolean last) {
            oversized |= fragments.length() + data.length() > MAX_EVENT_CHARS;
            if (!oversized) {
                fragments.append(data);
            }
            if (last) {
                if (!oversized) {
                    receive(fragments.toString());
                }
                fragments.setLength(0);
                oversized = false;
            }
            socket.request(1);
            return null;
        }

        /** 接收完整 JSON 文本帧：优先按 {@code echo} 唤醒等待中的同步动作调用，否则按入站消息过滤后送入有界队列。 */
        private void receive(String text) {
            JsonObject event;
            try {
                event = JSON.fromJson(text, JsonObject.class);
            } catch (JsonParseException error) {
                System.err.println("OpenQQ 返回了无效 JSON 事件");
                return;
            }
            if (event == null) {
                return;
            }
            var action = pending;
            if (action != null && action.echo().equals(string(event.get("echo")))) {
                action.response().complete(event);
                return;
            }
            var message =
                    incoming(
                            event,
                            accountId,
                            allowedUserId,
                            allowedGroupId,
                            writes.map(RouterMemoryWriter::authorGrantsEnabled).orElse(false));
            if (message != null && !inbox.offer(message)) {
                System.err.println("OpenQQ 入站队列已满");
            }
        }

        @Override
        public CompletableFuture<Void> onClose(WebSocket socket, int statusCode, String reason) {
            disconnect();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnect();
        }

        private void disconnect() {
            ended = true;
            var action = pending;
            if (action != null) {
                action.response().completeExceptionally(new IOException("OpenQQ 连接已关闭"));
            }
        }
    }
}
