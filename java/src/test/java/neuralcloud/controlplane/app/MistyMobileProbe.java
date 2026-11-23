package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;

/** 真机验收入口。受信任的测试渠道通过进程管道提供认证成员及原始消息，模型走正式编排器。 */
public final class MistyMobileProbe {
    private static final Gson JSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--config")) throw new IllegalArgumentException("需要 --config 配置路径");
        var config = ConsoleConfig.load(ConfigSource.file(Path.of(args[1])));
        var memory = MemoryConfig.load(config.memoryConfig().orElseThrow());
        if (memory.apiVersion() != 2 || !memory.scopedRecall()) throw new IllegalArgumentException("移动联调需要 v2 scoped Memory");
        var chat = ChatWorkerConfig.load(config.chatWorkerConfig());
        var router = MasterConfig.load(config.routerConfig());
        var master = MasterConfig.load(config.masterConfig());
        var opened = new ArrayDeque<AutoCloseable>();
        try (var audit = AuditLog.open(config.auditDirectory()); var chatHttp = chat.newClient();
                var routerHttp = router.newClient(); var masterHttp = master.newClient();
                var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var store = ConsoleMain.openMemory(Optional.of(memory), opened, audit, false).orElseThrow();
            var exchange = ConsoleMain.plannedConversation(new Master(masterHttp, master),
                    new Router<>(new Master(routerHttp, router), config.routerRolePrompt().readText(), Route.class),
                    chat.create(chatHttp, chat.apiKey(), config.chatWorkerRolePrompt().readText()), store,
                    config.masterMaxTokens(), config.masterTemperature());
            emit(Map.of("ready", true));
            for (String line; (line = input.readLine()) != null;) {
                turn(input, JsonParser.parseString(line).getAsJsonObject(), audit, exchange, store);
            }
        } finally {
            while (!opened.isEmpty()) opened.pop().close();
        }
    }

    /** 生成与确认是两步。移动服务持久化同一条回复后返回其 ID，才允许登记送达。 */
    private static void turn(BufferedReader input, JsonObject inbound, AuditLog audit,
            ConsoleServer.Exchange exchange, ConsoleMain.MemoryAccess store) throws Exception {
        var member = new Trace.MobileMember(inbound.get("instance").getAsString(), inbound.get("member_id").getAsLong());
        String text = inbound.get("text").getAsString();
        long id = inbound.get("message_id").getAsLong();
        int request = audit.write(member.conversationId(), null, "mistyai", "mobile_message",
                Map.of("text", text, "messageId", id));
        var trace = Trace.forMobilePrivate(audit, request, member);
        var reply = exchange.send(text, trace);
        if (!"chat_worker_reply".equals(reply.type())) {
            trace.write(reply.causedBy(), "mistyai", reply.type(), reply.data());
            throw new IOException("本轮未生成 Chat Worker 回复：" + reply.type() + " " + reply.data());
        }
        int event = trace.write(reply.causedBy(), "chat-worker", reply.type(), reply.data());
        store.writes().verifyDelivery(trace);
        String answer = (String) reply.data().get("text");
        emit(trace.delivery(Map.of("reply_to", id, "text", answer, "event", event)));
        String line = input.readLine();
        if (line == null) throw new IOException("移动端未提供送达回执");
        var receipt = JsonParser.parseString(line).getAsJsonObject();
        if (receipt.get("reply_to").getAsLong() != id || receipt.get("message_id").getAsLong() <= id)
            throw new IOException("移动服务回执未匹配本次私聊");
        int delivered = trace.write(event, "mistyai", "mobile_delivery",
                trace.delivery(Map.of("messageId", receipt.get("message_id").getAsLong())));
        store.writes().rememberDelivered(trace, delivered, answer);
        emit(Map.of("delivered", delivered, "run_id", audit.runId()));
    }

    /** 标准输出仅用于测试渠道的逐行 JSON 协议，诊断异常保留在标准错误。 */
    private static void emit(Map<String, ?> data) {
        System.out.println(JSON.toJson(data));
        System.out.flush();
    }
}
