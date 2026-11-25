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
import neuralcloud.controlplane.console.ConsoleServer.Reply;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.ChatImage;
import neuralcloud.controlplane.worker.ChatWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * 2. 按群策略或静态白名单接收文本和图片；原作者私聊 {@code /memory} 控制命令只接受纯文本；
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
    /** OpenQQ 的 get_image 最多内联 16 MiB；仅其在途回执可使用较大的帧预算。 */
    private static final int MAX_IMAGE_FRAME_CHARS = 4 * ((16 * 1024 * 1024 + 2) / 3) + MAX_EVENT_CHARS;
    /** 单条有效纯文本消息的最大字符数。 */
    private static final int MAX_MESSAGE_CHARS = 4096;

    /** 图片标号与原始消息段顺序一致，媒体标识只在本次已认证的 OneBot 连接中使用。 */
    record Incoming(
            long messageId,
            String text,
            long groupId,
            long senderId,
            boolean addressed,
            QqGroups.Policy policy,
            List<String> imageKeys,
            Trace.QqMessage metadata) {
        /** 动态观察群和已受理的静态群互聊都只向记忆写入原文。 */
        boolean observeOnly() {
            return policy == null ? groupId != 0 && !addressed : policy.policy() == QqGroups.Mode.OBSERVE;
        }

        Incoming(long messageId, String text, long groupId, long senderId) {
            this(messageId, text, groupId, senderId, true, null, List.of(), Trace.QqMessage.EMPTY);
        }
    }

    /** 延迟读取当前消息附件，调用方在原文入账并检查接收策略之后才可取图。 */
    @FunctionalInterface
    public interface Images {
        List<ChatImage> read() throws IOException, InterruptedException;
    }

    /** 受理的消息只入账一次。策略和寻址决定是否经过参与判断，再进入已验收的编排链。 */
    @FunctionalInterface
    public interface ManagedExchange {
        Optional<Reply> send(String text, Trace trace, QqGroups.Mode mode, boolean addressed, Images images)
                throws IOException, InterruptedException;
    }

    /** 正在等待 OneBot 回执的同步动作请求（通过唯一 {@code echo} 字段与返回帧配对）。 */
    private record Pending(String echo, CompletableFuture<JsonObject> response, boolean image) {}

    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final long accountId;
    private final long allowedUserId;
    private final long allowedGroupId;
    private final AuditLog audit;
    private final Optional<RouterMemoryWriter> writes;
    private final Optional<QqGroups> groups;
    private final boolean observeStaticGroup;
    private final ManagedExchange managed;
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
            Optional<RouterMemoryWriter> writes,
            Optional<QqGroups> groups,
            ManagedExchange managed) {
        this(client, endpoint, token, accountId, allowedUserId, allowedGroupId, audit, writes, groups, false, managed);
    }

    /** 启动方在 Dreaming 能力握手后才为静态白名单开启普通成员文本的观察入口。 */
    public OpenQqChannel(
            HttpClient client, URI endpoint, String token, long accountId, long allowedUserId, long allowedGroupId,
            AuditLog audit, Optional<RouterMemoryWriter> writes, Optional<QqGroups> groups,
            boolean dreamingEnabled, ManagedExchange managed) {
        this.client = client;
        this.endpoint = endpoint;
        this.token = token;
        this.accountId = accountId;
        this.allowedUserId = allowedUserId;
        this.allowedGroupId = allowedGroupId;
        this.audit = audit;
        this.writes = writes;
        this.groups = groups;
        this.observeStaticGroup = dreamingEnabled && allowedGroupId != 0 && groups.isEmpty();
        this.managed = managed;
        this.runner = Thread.ofVirtual().name("openqq-channel").unstarted(this::run);
    }

    /** 两条入站队列共用原子去重与受理边界，旧策略及新策略下的重复投递也只登记一次。 */
    private int accept(String chain, Incoming incoming, boolean decision) throws IOException {
        synchronized (audit) {
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
                return 0;
            }
            return audit.write(
                    chain,
                    null,
                    "openqq",
                    decision ? "qq_author_command" : "qq_message",
                    incoming.imageKeys().isEmpty()
                            ? Map.of("text", incoming.text(), "messageId", incoming.messageId())
                            : Map.of("text", incoming.text(), "messageId", incoming.messageId(),
                                    "imageCount", incoming.imageKeys().size()));
        }
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
     * 3. 授权命令直接转交 {@link RouterMemoryWriter#authorDecision}，普通消息交对话处理器并在外发前复核引用；
     * 4. 通过 OneBot {@code send_private_msg}/{@code send_group_msg} 发送文本；仅在收到含有效 {@code message_id} 的成功回执后才记 {@code qq_delivery} 并落盘角色回复。
     */
    private void handle(Session session, WebSocket socket, Incoming incoming) throws IOException, InterruptedException {
        boolean group = incoming.groupId() != 0;
        if (group
                && groups.isPresent()
                && !incoming.policy().equals(groups.get().current(incoming.groupId()))) return;
        String chain =
                group
                        ? "qq-group-" + accountId + "-" + incoming.groupId()
                        : "qq-private-" + accountId + "-" + incoming.senderId();
        boolean decision = authorCommand(incoming.text());
        if (group && decision) return;
        int request = accept(chain, incoming, decision);
        if (request == 0) return;
        var trace =
                group
                        ? Trace.forQqGroup(
                                audit,
                                chain,
                                request,
                                new Trace.QqGroup(
                                        accountId,
                                        incoming.groupId(),
                                        incoming.senderId(),
                                        groups.isPresent(),
                                        groups.isPresent() && incoming.observeOnly() ? incoming.policy().revision() : 0,
                                        incoming.metadata()))
                        : Trace.forQqPrivate(
                                audit,
                                chain,
                                request,
                                new Trace.QqPrivate(
                                        accountId,
                                        incoming.senderId(),
                                        groups.isPresent() && !decision
                                                ? session.memberships(socket, incoming.senderId())
                                                : List.of(),
                                        incoming.metadata()));
        Reply reply;
        try {
            if (incoming.observeOnly()) {
                writes.orElseThrow().rememberInbound(trace, request, incoming.text());
                return;
            }
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
                var result = managed.send(incoming.text(), trace,
                        incoming.policy() == null ? QqGroups.Mode.OBSERVE_REPLY : incoming.policy().policy(),
                        incoming.addressed(), () -> session.images(socket, incoming));
                if (result.isEmpty()) return;
                reply = result.get();
                if (writes.isPresent()) writes.get().verifyDelivery(trace);
            }
        } catch (IOException error) {
            int failed =
                    trace.write(
                            request,
                            decision || incoming.observeOnly() ? "memory" : "chat-worker",
                            "node_failed",
                            Map.of("detail", error.toString()));
            if (decision)
                reply =
                        new Reply(
                                "author_decision_reply",
                                Map.of("text", "未取得授权决定的回执。" + error.getMessage()),
                                failed);
            else if (error instanceof ChatWorker.PlanningFailure) {
                // 参与轮次有明确出口。渠道故障、授权改变和仅观察仍走各自的协议，不能触发重复外发。
                error.printStackTrace(System.err);
                reply = new Reply("chat_worker_failure_notice", Map.of("text", "这次没能完成查证和回复。"), failed);
            } else {
                error.printStackTrace(System.err);
                return;
            }
        }
        boolean failureNotice = reply.type().equals("chat_worker_failure_notice");
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
            var segments =
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "data",
                                    Map.of("text", reply.data().get("text"))));
            var params =
                    Map.of(
                            group ? "group_id" : "user_id",
                            group ? incoming.groupId() : incoming.senderId(),
                            "message",
                            segments);
            List<Long> evidenceGroups =
                    failureNotice || trace.sharedSources().isEmpty()
                            ? List.of()
                            : trace.scope()
                                    .qq()
                                    .map(Trace.QqPrivate::memberGroups)
                                    .orElse(List.of());
            if (!evidenceGroups.isEmpty()
                    && !session.memberships(socket, incoming.senderId())
                            .containsAll(evidenceGroups)) throw new PolicyChanged();
            receipt =
                    session.call(
                            socket,
                            group ? "send_group_msg" : "send_private_msg",
                            params,
                            incoming,
                            evidenceGroups);
        } catch (PolicyChanged error) {
            trace.write(
                    sending, "openqq", "qq_send_cancelled", Map.of("reason", "群策略或已核验的成员身份已变更"));
            return;
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
                            failureNotice ? Map.of("messageId", id(data.get("message_id")))
                                    : trace.delivery(Map.of("messageId", id(data.get("message_id")))));
            if (!decision && !failureNotice && writes.isPresent()) {
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
     * 管理模式按群策略接收，静态白名单的对话要求点名；启用 Dreaming 后另接收成员互聊的文字。
     * 非白名单私聊用户仅能提交纯文本的原作者授权命令。
     */
    static Incoming incoming(
            JsonObject event,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            boolean authorCommands) {
        return incoming(event, accountId, allowedUserId, allowedGroupId, authorCommands, null);
    }

    /** 保留未配置后台整理的接收入口。 */
    static Incoming incoming(
            JsonObject event,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            boolean authorCommands,
            QqGroups groups) {
        return incoming(event, accountId, allowedUserId, allowedGroupId, authorCommands, groups, false);
    }

    /** 在解析正文前核对消息来源；静态群中未点名机器人的图文只取成员自己提交的文字。 */
    private static Incoming incoming(
            JsonObject event, long accountId, long allowedUserId, long allowedGroupId,
            boolean authorCommands, QqGroups groups, boolean observeStaticGroup) {
        long sender = id(event.get("user_id"));
        boolean group = "group".equals(string(event.get("message_type")));
        long gid = id(event.get("group_id"));
        var policy = group && groups != null ? groups.current(gid) : null;
        if (group
                ? (policy == null
                                ? allowedGroupId == 0 || gid != allowedGroupId
                                : policy.policy() == QqGroups.Mode.OFF)
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
        var text = new StringBuilder();
        var caption = observeStaticGroup && group ? new StringBuilder() : null;
        var images = new ArrayList<String>();
        var mentions = new LinkedHashSet<String>();
        String reply = "";
        boolean addressed = false;
        for (var item : segments) {
            if (!(item instanceof JsonObject segment)
                    || !(segment.get("data") instanceof JsonObject data)) return null;
            if ("at".equals(string(segment.get("type")))) {
                String target = string(data.get("qq"));
                if (target.equals(Long.toString(accountId)) && group) addressed = true;
                else if (!target.matches("[1-9][0-9]{4,15}") || target.equals(Long.toString(accountId))) return null;
                else mentions.add(target);
                if (mentions.size() > 64) return null;
                continue;
            }
            if ("reply".equals(string(segment.get("type")))) {
                String target = string(data.get("id"));
                if (!reply.isEmpty() || target.isBlank() || target.codePointCount(0, target.length()) > 128) return null;
                reply = target;
                continue;
            }
            if ("image".equals(string(segment.get("type")))) {
                // OpenQQ 的 file_id 与 file 都是当前连接媒体索引的键；不使用 path、url 或用户文字。
                String key = string(data.has("file_id") ? data.get("file_id") : data.get("file"));
                if (key.isBlank() || key.length() > 1024 || key.chars().anyMatch(c -> c < 32)) {
                    System.err.println("OpenQQ 图片段须有合法的 file_id 或 file");
                    return null;
                }
                images.add(key);
                text.append("【图片").append(images.size()).append("】");
                continue;
            }
            if (!"text".equals(string(segment.get("type")))
                    || !(data.get("text") instanceof JsonPrimitive part)
                    || !part.isString()) {
                return null;
            }
            text.append(part.getAsString());
            if (caption != null) caption.append(part.getAsString());
        }
        String body = caption != null && !addressed ? caption.toString() : text.toString();
        if (policy != null && addressed && body.isBlank()) body = "@" + accountId;
        if (body.isBlank()
                || body.length() > MAX_MESSAGE_CHARS
                || group && policy == null && !addressed && !observeStaticGroup) return null;
        // OneBot 群成员互聊的 message_id 是有符号 int32。账号、前台及通知仍使用各自原有的 ID 边界。
        var field = event.get("message_id");
        long messageId;
        if (group && !addressed && (policy == null || !Trace.QqGroup.isNoticeCommand(body))) {
            if (!(field instanceof JsonPrimitive number) || !number.isNumber()) return null;
            try {
                messageId = Integer.parseInt(number.getAsString());
            } catch (NumberFormatException invalidMessageId) {
                return null;
            }
        } else {
            messageId = id(field);
            if (messageId <= 0) return null;
        }
        // 允许原作者发控制命令，无须开放其普通私聊。身份始终取自已认证连接的事件。
        if (authorCommand(body) && (!images.isEmpty() || !mentions.isEmpty() || !reply.isEmpty())
                || !group && sender != allowedUserId && !(authorCommands && authorCommand(body)))
            return null;
        // 称呼绑定外层认证的发件人。文本中的 CQ 码、转发原文及他人的资料不能提供这些字段。
        if (event.has("sender") && !(event.get("sender") instanceof JsonObject)) return null;
        var profile = event.getAsJsonObject("sender");
        if (profile != null && profile.has("user_id") && id(profile.get("user_id")) != sender) return null;
        String nickname = displayName(profile, "nickname");
        String card = group ? displayName(profile, "card") : "";
        if (nickname == null || card == null) return null;
        return new Incoming(messageId, body, group ? gid : 0, sender, !group || addressed, policy,
                List.copyOf(images), new Trace.QqMessage(nickname, card, List.copyOf(mentions), reply));
    }

    /** 缺少显示称呼或平台给出空名片时没有对应线索。类型或长度超出契约时拒收该事件。 */
    private static String displayName(JsonObject sender, String field) {
        if (sender == null || !sender.has(field)) return "";
        if (sender.get(field) instanceof JsonPrimitive value && value.isString()) {
            String name = value.getAsString();
            if (name.codePointCount(0, name.length()) <= 128) return name.isBlank() ? "" : name;
        }
        return null;
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

    private static final class PolicyChanged extends IOException {}

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
        private final ArrayBlockingQueue<Incoming> observations = new ArrayBlockingQueue<>(32);
        private StringBuilder fragments = new StringBuilder();
        private boolean oversized;
        private volatile boolean ended;
        private boolean ownsRoster; // 只在群策略锁下访问，旧连接的迟到关闭通知不能清空新名单。
        private volatile Pending pending;

        private void run() throws IOException, ExecutionException, TimeoutException, InterruptedException {
            var connection = client.newWebSocketBuilder().connectTimeout(IO_TIMEOUT)
                    .header("Authorization", "Bearer " + token).buildAsync(endpoint, this);
            Thread observer = null;
            try {
                var socket = connection.get(IO_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                var login = call(socket, "get_login_info", Map.of());
                if (!successful(login) || !(login.get("data") instanceof JsonObject data)
                        || id(data.get("user_id")) != accountId) {
                    throw new IOException("OpenQQ 登录账号未就绪或与配置不符");
                }
                System.out.println("OpenQQ 私聊接入已就绪");
                // 观察群原文有独立的有序接收线程，前台 QQ 推理和发送回执不会挡住其入库。
                if (groups.isPresent() || observeStaticGroup)
                    observer = Thread.ofVirtual().name("openqq-observe-ingress").start(() -> observe(socket));
                long nextRoster = 0;
                while (!closed && !ended) {
                    if (groups.isPresent()
                            && (nextRoster == 0 || System.nanoTime() - nextRoster >= 0)) {
                        var roster = call(socket, "get_group_list", Map.of());
                        if (!successful(roster) || !(roster.get("data") instanceof JsonArray rooms))
                            throw new IOException("OpenQQ 未返回已登录账号的群列表");
                        synchronized (groups.get()) {
                            if (ended) return;
                            groups.get().roster(rooms);
                            ownsRoster = true;
                        }
                        nextRoster = System.nanoTime() + Duration.ofMinutes(1).toNanos();
                    }
                    var message = inbox.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        handle(this, socket, message);
                    }
                }
            } finally {
                disconnect();
                connection.thenAccept(WebSocket::abort);
                if (observer != null) { observer.interrupt(); observer.join(); }
            }
        }

        private void observe(WebSocket socket) {
            try {
                while (!ended) {
                    var incoming = observations.take();
                    try {
                        handle(this, socket, incoming);
                    } catch (IOException failure) {
                        failure.printStackTrace(System.err);
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }

        /** 发送一条带随机 {@code echo} 的 OneBot JSON 动作帧，并阻塞等待对应回执或超时。 */
        private JsonObject call(WebSocket socket, String action, Map<String, ?> params)
                throws IOException, InterruptedException {
            return call(socket, action, params, null, List.of());
        }

        /** 整批取图成功后才提交模型；群在等待期间改策略时取消本轮，不把图片交给外部模型。 */
        private List<ChatImage> images(WebSocket socket, Incoming incoming) throws IOException, InterruptedException {
            if (incoming.imageKeys().size() > ChatImage.MAX_COUNT)
                throw new IOException("单条 QQ 消息最多识别 " + ChatImage.MAX_COUNT + " 张图片");
            var images = new ArrayList<ChatImage>();
            int remaining = ChatImage.MAX_TOTAL_BYTES;
            for (String key : incoming.imageKeys()) {
                var reply = call(socket, "get_image", Map.of("file", key), incoming, List.of());
                if (!successful(reply) || !(reply.get("data") instanceof JsonObject data)
                        || !(data.get("base64") instanceof JsonPrimitive raw) || !raw.isString())
                    throw new IOException("OpenQQ get_image 未返回可读取的图片字节");
                var image = ChatImage.fromBase64(raw.getAsString(), remaining);
                remaining -= image.size();
                images.add(image);
            }
            if (incoming.policy() != null && !incoming.policy().equals(groups.orElseThrow().current(incoming.groupId())))
                throw new PolicyChanged();
            return images;
        }

        /** 仅 get_group_member_info 成功且回包群号、成员号均匹配时签入该群，拒绝或未知不授予引用权。 */
        private List<Long> memberships(WebSocket socket, long peer)
                throws IOException, InterruptedException {
            var verified = new java.util.ArrayList<Long>();
            for (long gid : groups.orElseThrow().observed()) {
                var result =
                        call(
                                socket,
                                "get_group_member_info",
                                Map.of("group_id", gid, "user_id", peer, "no_cache", true));
                if (successful(result)) {
                    if (!(result.get("data") instanceof JsonObject data)
                            || id(data.get("group_id")) != gid
                            || id(data.get("user_id")) != peer)
                        throw new IOException("OneBot 成员回执缺少匹配的群号或成员号");
                    verified.add(gid);
                } else if (!("failed".equals(string(result.get("status")))
                        && new JsonPrimitive(1404).equals(result.get("retcode"))))
                    throw new IOException("OpenQQ 成员查询未取得有效回执");
            }
            return verified;
        }

        /** 与面板修改在发送起点互斥，只在发出动作帧时持锁；等待 echo 期间收帧线程仍能受理事件。 */
        private JsonObject call(
                WebSocket socket,
                String action,
                Map<String, ?> params,
                Incoming delivery,
                List<Long> evidenceGroups)
                throws IOException, InterruptedException {
            var request = new Pending(UUID.randomUUID().toString(), new CompletableFuture<>(), "get_image".equals(action));
            pending = request;
            try {
                if (ended) {
                    throw new IOException("OpenQQ 连接已关闭");
                }
                String frame =
                        JSON.toJson(
                                Map.of("action", action, "params", params, "echo", request.echo()));
                CompletableFuture<WebSocket> sent;
                if (delivery != null && (delivery.policy() != null || !evidenceGroups.isEmpty())) {
                    var guard = groups.orElseThrow();
                    synchronized (guard) {
                        if (delivery.policy() != null
                                        && !delivery.policy()
                                                .equals(guard.current(delivery.groupId()))
                                || !evidenceGroups.isEmpty()
                                        && !guard.observed().containsAll(evidenceGroups))
                            throw new PolicyChanged();
                        sent = socket.sendText(frame, true);
                    }
                } else sent = socket.sendText(frame, true);
                sent.get(IO_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
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
            var action = pending;
            int limit = action != null && action.image() ? MAX_IMAGE_FRAME_CHARS : MAX_EVENT_CHARS;
            oversized |= data.length() > limit - fragments.length();
            if (!oversized) {
                fragments.append(data);
            }
            if (last) {
                if (!oversized) {
                    receive(fragments.toString());
                }
                if (fragments.capacity() > MAX_EVENT_CHARS) fragments = new StringBuilder();
                else fragments.setLength(0);
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
            if (text.length() > MAX_EVENT_CHARS) return;
            var message =
                    incoming(
                            event,
                            accountId,
                            allowedUserId,
                            allowedGroupId,
                            writes.map(RouterMemoryWriter::authorGrantsEnabled).orElse(false),
                            groups.orElse(null),
                            observeStaticGroup);
            if (message != null && !(message.observeOnly() ? observations : inbox).offer(message)) {
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
            groups.ifPresent(
                    roster -> {
                        synchronized (roster) {
                            if (ownsRoster) roster.disconnected();
                            ownsRoster = false;
                        }
                    });
            var action = pending;
            if (action != null) {
                action.response().completeExceptionally(new IOException("OpenQQ 连接已关闭"));
            }
        }
    }
}
