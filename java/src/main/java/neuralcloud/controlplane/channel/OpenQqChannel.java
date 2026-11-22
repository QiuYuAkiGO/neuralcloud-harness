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
import neuralcloud.controlplane.memory.ConversationMemory;

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

    record Incoming(long messageId, String text) {}
    private record Pending(String echo, CompletableFuture<JsonObject> response) {}

    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final long accountId;
    private final long allowedUserId;
    private final String chain;
    private final AuditLog audit;
    private final Exchange conversation;
    private final Optional<ConversationMemory> memory;
    private final Thread runner;
    private volatile boolean closed;

    public OpenQqChannel(HttpClient client, URI endpoint, String token, long accountId, long allowedUserId,
                         AuditLog audit, Exchange conversation, Optional<ConversationMemory> memory) {
        this.client = client;
        this.endpoint = endpoint;
        this.token = token;
        this.accountId = accountId;
        this.allowedUserId = allowedUserId;
        this.chain = "qq-private-" + accountId + "-" + allowedUserId;
        this.audit = audit;
        this.conversation = conversation;
        this.memory = memory;
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
        // 先持久化入站标识。断线或重启重见同一事件时，不重复执行或发送。
        if (audit.events(chain).stream().anyMatch(event -> "qq_message".equals(event.type())
                && new JsonPrimitive(incoming.messageId()).equals(event.data().getAsJsonObject().get("messageId")))) {
            return;
        }
        int request = audit.write(chain, null, "openqq", "qq_message",
                Map.of("text", incoming.text(), "messageId", incoming.messageId()));
        var trace = Trace.forQqPrivate(audit, chain, request, accountId, allowedUserId);
        Reply reply;
        try {
            reply = conversation.send(incoming.text(), trace);
        } catch (IOException error) {
            trace.write(request, "chat-worker", "node_failed", Map.of("detail", error.toString()));
            error.printStackTrace(System.err);
            return;
        }
        int drafted = trace.write(reply.causedBy() == null ? request : reply.causedBy(),
                "chat-worker", reply.type(), reply.data());
        int sending = trace.write(drafted, "openqq", "qq_send_requested", Map.of());
        JsonObject receipt;
        try {
            receipt = session.call(socket, "send_private_msg", Map.of("user_id", allowedUserId,
                    "message", List.of(Map.of("type", "text", "data", Map.of("text", reply.data().get("text"))))));
        } catch (IOException error) {
            // 发送超时无法区分已提交与未提交，保留不确定回执，禁止自动重发。
            trace.write(sending, "openqq", "qq_delivery_unknown", Map.of("detail", error.getMessage()));
            return;
        }
        if (successful(receipt) && receipt.get("data") instanceof JsonObject data
                && id(data.get("message_id")) > 0) {
            int delivered = trace.write(sending, "openqq", "qq_delivery", Map.of("messageId", id(data.get("message_id"))));
            if (memory.isPresent()) {
                memory.orElseThrow().rememberDelivered(trace, delivered, (String) reply.data().get("text"));
            }
        } else if ("failed".equals(string(receipt.get("status")))
                && receipt.get("retcode") instanceof JsonPrimitive code && code.isNumber()) {
            trace.write(sending, "openqq", "qq_delivery_failed", Map.of("retcode", code.getAsNumber()));
        } else {
            trace.write(sending, "openqq", "qq_delivery_unknown", Map.of("detail", "OneBot 回执缺少有效消息 ID"));
        }
    }

    static Incoming incoming(JsonObject event, long accountId, long allowedUserId) {
        // OpenQQ 将原生普通私聊标为 other，OneBot 的标准好友私聊标为 friend。
        if (!"message".equals(string(event.get("post_type")))
                || !"private".equals(string(event.get("message_type")))
                || !List.of("friend", "other").contains(string(event.get("sub_type")))
                || id(event.get("self_id")) != accountId || id(event.get("user_id")) != allowedUserId
                || !(event.get("message") instanceof JsonArray segments)) {
            return null;
        }
        long messageId = id(event.get("message_id"));
        var text = new StringBuilder();
        for (var item : segments) {
            if (!(item instanceof JsonObject segment) || !"text".equals(string(segment.get("type")))
                    || !(segment.get("data") instanceof JsonObject data)
                    || !(data.get("text") instanceof JsonPrimitive part) || !part.isString()) {
                return null;
            }
            text.append(part.getAsString());
        }
        String body = text.toString();
        return messageId > 0 && !body.isBlank() && body.length() <= MAX_MESSAGE_CHARS
                ? new Incoming(messageId, body) : null;
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
            var message = incoming(event, accountId, allowedUserId);
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
