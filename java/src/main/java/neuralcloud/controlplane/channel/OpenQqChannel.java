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

public final class OpenQqChannel implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_EVENT_CHARS = 65536;
    private static final int MAX_MESSAGE_CHARS = 4096;

    record Incoming(long messageId, String text, long groupId, long senderId) {}

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

    public void start() {
        runner.start();
    }

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

    private static boolean authorCommand(String text) {
        return text.equals("/memory") || text.startsWith("/memory ");
    }

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
