package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterRuntime;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.memory.MemoryJournal;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.orchestration.MasterEpochs;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.worker.ChatWorker;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static neuralcloud.controlplane.app.DirectMemoryCallTest.body;
import static neuralcloud.controlplane.app.DirectMemoryCallTest.respond;
import static neuralcloud.controlplane.app.DirectMemoryCallTest.tool;
import static org.junit.Assert.*;

/** 在正式记忆工具及对话编排入口观测身份归属、下一轮模型输入、渠道复核与回复写入请求。 */
public class IdentityLookupTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final String OWNER = "34567";
    private static final String NAME = "银杏七号";
    private static final String ID = "d".repeat(64);
    private static final String STREAM = "54f72df6-511a-4044-ab81-d66006b7ce1b";
    private static final Map<String, Object> REF = Map.of("record_id", ID, "revision", 1);
    private static final String LOOKUP = "请核实登记过的称呼。";
    private static final String NEXT = "换个话题。";
    private final List<JsonObject> recalls = new CopyOnWriteArrayList<>();
    private final List<JsonObject> reads = new CopyOnWriteArrayList<>();
    private final List<JsonObject> chatInputs = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> changes = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<JsonObject> deliveredWrites = new LinkedBlockingQueue<>();
    private volatile boolean isolated;
    private HttpServer backend;
    private HttpClient http;
    private AuditLog audit;
    private MemoryJournal journal;
    private MasterEpochs epochs;
    private ConversationMemory memory;
    private RouterMemoryWriter writer;
    private ConsoleServer.Exchange conversation;

    /** Memory 与推理只监听本机。启动真实 Memory 客户端握手，之后渠道和模型均走正式入口。 */
    @Before public void open() throws Exception {
        changes.add(Map.of("seq", 1, "op", "INGEST", "record_id", ID, "revision", 1));
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/v2/health", ex -> respond(ex, JSON.toJson(Map.of("api_major", 2,
                "status", "TEXT_READY", "character_id", "misty", "stream_id", STREAM, "head_seq", changes.size(),
                "session_isolation", isolated, "capabilities", List.of("durable_ingest", "read_refs", "scoped_recall",
                        "changes", "self_identity", "self_history", "assertions", "candidates", "corrections",
                        "audience_reads", "identity_lookup")))));
        backend.createContext("/v2/changes", ex -> {
            var cursor = new LinkedHashMap<String, Integer>();
            for (String part : ex.getRequestURI().getQuery().split("&")) {
                String[] field = part.split("=", 2);
                if (List.of("after", "through").contains(field[0])) cursor.put(field[0], Integer.parseInt(field[1]));
            }
            int through = cursor.getOrDefault("through", changes.size());
            respond(ex, JSON.toJson(Map.of("stream_id", STREAM, "through", through, "next_after", through,
                    "items", changes.subList(cursor.get("after"), through))));
        });
        backend.createContext("/v2/recall", this::recall);
        backend.createContext("/v2/read", this::read);
        backend.createContext("/v2/receipts", ex -> respond(ex, "{\"status\":\"NOT_FOUND\"}"));
        backend.createContext("/v2/ingest", this::ingest);
        backend.createContext("/v1/chat/completions", this::model);
        backend.start();
        var directory = folder.getRoot().toPath();
        http = HttpClient.newHttpClient();
        audit = AuditLog.open(directory);
        journal = new MemoryJournal(directory.resolve("worker.sqlite"));
        epochs = new MasterEpochs(directory.resolve("epochs.sqlite"), MasterEpochs.Profile.DEFAULT);
        URI base = URI.create("http://127.0.0.1:" + backend.getAddress().getPort());
        var resident = new ResidentV2(http, base, "misty", "fixture", true, journal, null);
        resident.verify(false);
        memory = new ConversationMemory(audit, resident);
        writer = new RouterMemoryWriter(audit, resident);
        var endpoint = base.resolve("/v1/chat/completions");
        var timeout = Duration.ofSeconds(3);
        var master = new Master(http, new Master.Settings(endpoint, "trial", HttpClient.Version.HTTP_1_1,
                timeout, timeout, "核对资料。", 8, 0, false));
        var chat = ChatWorker.openAiChat(http, endpoint, HttpClient.Version.HTTP_1_1, "trial", "fixture",
                timeout, 1024, false, Master.ThinkingFormat.DEEPSEEK, "根据材料作答。", "简短回答。");
        conversation = ConsoleMain.plannedConversation(new MasterRuntime(master, epochs, 256, 0),
                new Router<>(master, "选择流程。", Route.class), chat, new ConsoleMain.MemoryAccess(memory, writer, Optional.empty()));
    }

    @After public void close() throws Exception {
        if (http != null) http.close();
        if (backend != null) backend.stop(0);
        if (epochs != null) epochs.close();
        if (journal != null) journal.close();
        if (audit != null) audit.close();
    }

    /** 群里可列举，其他私聊可按账号查询，移动端可按称呼查询；引用与送达都保留身份所有者和用途。 */
    @Test public void channelsCanCiteAnotherAccountsConfirmedIdentityWithoutRenamingTheSender() throws Exception {
        int group = audit.write("group", null, "openqq", "qq_message", Map.of("text", LOOKUP));
        checkTool(Trace.forQqGroup(audit, "group", group, new Trace.QqGroup(10001, 20001, 10002, true)), "");
        int peer = audit.write("private", null, "openqq", "qq_message", Map.of("text", LOOKUP));
        checkTool(Trace.forQqPrivate(audit, "private", peer, 10001, 10002), OWNER);

        var member = new Trace.MobileMember("trial", 2);
        int event = audit.write(member.conversationId(), null, "mistyai", "mobile_message", Map.of("text", LOOKUP, "messageId", 1));
        var trace = Trace.forMobilePrivate(audit, event, member);
        var reply = conversation.send(LOOKUP, trace);
        assertOffersIdentity(chatInputs.getFirst().getAsJsonArray("tools").get(0).getAsJsonObject());
        assertEquals(NAME, recalls.getLast().get("query").getAsString());
        assertAttributed(chatInputs.getLast().getAsJsonArray("messages").toString());
        int draft = trace.write(reply.causedBy(), "chat-worker", reply.type(), reply.data());
        writer.verifyDelivery(trace);
        int delivered = trace.write(draft, "mistyai", "mobile_delivery", trace.delivery(Map.of("messageId", 2)));
        writer.rememberDelivered(trace, delivered, (String) reply.data().get("text"));
        var submission = deliveredWrites.poll(3, TimeUnit.SECONDS);
        assertNotNull("已送达的移动回复应写入 Memory", submission);
        assertTrue(submission.getAsJsonArray("source_refs").asList().contains(JSON.toJsonTree(
                Map.of("record_id", ID, "revision", 1, "purpose", "IDENTITY"))));
    }

    /** 只收回身份披露，即使同一记录的普通历史仍可引用，未发出的回复也须拦下，下轮须移除旧身份。 */
    @Test public void ordinaryReadPermissionCannotAuthorizeIdentityDeliveryOrNextTurnReuse() throws Exception {
        var first = console(LOOKUP);
        conversation.send(LOOKUP, first);
        assertOffersIdentity(chatInputs.getFirst().getAsJsonArray("tools").get(0).getAsJsonObject());
        assertAttributed(chatInputs.getLast().getAsJsonArray("messages").toString());
        isolated = true;
        reads.clear();
        assertThrows(IOException.class, () -> writer.verifyDelivery(first));
        assertTrue("发送前应按身份用途回读该来源", reads.stream().anyMatch(IdentityLookupTest::readsIdentitySource));
        chatInputs.clear();
        conversation.send(NEXT, console(NEXT));
        assertTrue("下轮首个 Chat Worker 输入应提示身份依据失效",
                chatInputs.getFirst().getAsJsonArray("messages").toString().contains("原依据已失效"));
        for (var input : chatInputs) {
            String messages = input.getAsJsonArray("messages").toString();
            assertFalse(messages.contains(NAME));
            assertFalse(messages.contains(OWNER));
        }
    }

    /** 和正式 Master 使用同一份原生工具描述、模型工具结果和已引用证据，检查材料不会冒充当前 QQ 发件人。 */
    private void checkTool(Trace trace, String query) throws Exception {
        var call = memory.bindMasterIntent(trace, trace.request()).orElseThrow();
        assertOffersIdentity(JSON.toJsonTree(call.tool()).getAsJsonObject());
        var messages = new ArrayList<Map<String, Object>>();
        call.appendToolResult(messages, "lookup", JSON.toJson(Map.of("kind", "IDENTITY", "query", query)), "");
        assertEquals(query, recalls.getLast().get("query").getAsString());
        assertEquals(trace.scope().conversationId(), recalls.getLast().getAsJsonObject("context").get("conversation_id").getAsString());
        assertAttributed((String)messages.getLast().get("content"));
        assertTrue(call.confirmedSenderName(List.of(new MemoryCall.EvidenceRef(ID, 1))).isEmpty());
    }

    private static void assertAttributed(String material) {
        assertTrue("材料应包含真实 QQ 账号", material.contains(OWNER));
        assertTrue("材料应包含该账号的已确认称呼", material.contains(NAME));
        assertFalse(material.contains("当前 QQ 私聊的发件人"));
    }

    private static void assertOffersIdentity(JsonObject tool) {
        var schema = tool.getAsJsonObject("function").getAsJsonObject("parameters").getAsJsonObject("properties");
        assertTrue("此受众协商了 IDENTITY，工具应向模型提供它",
                schema.getAsJsonObject("kind").getAsJsonArray("enum").asList().contains(JSON.toJsonTree("IDENTITY")));
    }

    private static boolean readsIdentitySource(JsonObject read) {
        return read.has("kind") && "IDENTITY".equals(read.get("kind").getAsString()) && !read.has("use")
                && read.getAsJsonArray("targets").asList().stream().anyMatch(target ->
                ID.equals(target.getAsJsonObject().get("record_id").getAsString()));
    }

    private Trace console(String text) throws IOException {
        int event = audit.write("desk", null, "chat-worker", "operator_message", Map.of("text", text));
        return Trace.forChain(audit, "console", "desk", event);
    }

    /** 身份目录只交付准确匹配的确认资料；同一记录作为普通 HISTORY 仍有有效授权。 */
    private Map<String, Object> item(JsonObject request, String record) {
        String purpose = request.has("kind") ? request.get("kind").getAsString() : "HISTORY";
        boolean identity = "IDENTITY".equals(purpose);
        if (!ID.equals(record) || (identity ? isolated || request.has("use") : !"HISTORY".equals(purpose)))
            return Map.of("record_id", record, "status", "NOT_FOUND");
        String scope = request.getAsJsonObject("context").get("conversation_id").getAsString();
        var result = new LinkedHashMap<String, Object>(Map.of("ref", REF, "source_refs", List.of(REF), "status", "ONLINE",
                "kind", "observation", "basis", "direct", "view", "L2", "text", identity ? NAME : "资料已经交接。",
                "occurred_at", "2026-11-18T09:00:00Z", "citation_scope", scope, "grant_dependent", true));
        if (identity) result.put("identity_account_id", OWNER);
        return result;
    }

    private void recall(HttpExchange exchange) throws IOException {
        var request = body(exchange);
        recalls.add(request);
        var found = item(request, ID);
        boolean matches = List.of("", OWNER, NAME).contains(request.get("query").getAsString()) && "ONLINE".equals(found.get("status"));
        respond(exchange, JSON.toJson(Map.of("stream_id", STREAM, "read_seq", changes.size(), "items", matches ? List.of(found) : List.of(),
                "assessment", Map.of("state", matches ? "DIRECT" : "NOT_FOUND", "sufficient", matches, "absence_confirmed", false))));
    }

    private void read(HttpExchange exchange) throws IOException {
        var request = body(exchange);
        reads.add(request);
        var items = request.getAsJsonArray("targets").asList().stream()
                .map(target -> item(request, target.getAsJsonObject().get("record_id").getAsString())).toList();
        respond(exchange, JSON.toJson(Map.of("stream_id", STREAM, "read_seq", changes.size(), "items", items)));
    }

    /** 模拟入站发言与已送达回复的持久回执；切换隔离只改变读取权限。 */
    private void ingest(HttpExchange exchange) throws IOException {
        var request = body(exchange);
        int seq = changes.size() + 1;
        String id = "%064x".formatted(seq);
        changes.add(Map.of("seq", seq, "op", "INGEST", "record_id", id, "revision", 1));
        if ("self_expression".equals(request.get("kind").getAsString())) deliveredWrites.add(request);
        respond(exchange, JSON.toJson(Map.of("status", "DURABLE", "record_id", id, "revision", 1, "seq", seq)));
    }

    /** 走日常聊天路由。挡板通过规划工具按称呼查询，只有收到了所属账号与称呼时才在草稿中引用。 */
    private void model(HttpExchange exchange) throws IOException {
        var request = body(exchange);
        String name = request.has("tools") ? request.getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString() : "";
        Object answer;
        if ("select_route".equals(name)) {
            answer = tool(name, "{\"route\":\"CONVERSATION\"}");
        } else {
            chatInputs.add(request);
            String prompt = request.getAsJsonArray("messages").toString();
            if ("plan_turn".equals(name)) {
                String args = prompt.contains(NEXT) ? JSON.toJson(Map.of("kind", "REPLY", "query", ""))
                        : JSON.toJson(Map.of("kind", "IDENTITY", "query", NAME));
                answer = tool(name, args);
            } else {
                answer = Map.of("content", prompt.contains(OWNER) && prompt.contains(NAME) ? OWNER + " 的称呼是 " + NAME : "收到。");
            }
        }
        respond(exchange, JSON.toJson(Map.of("model", "trial", "choices", List.of(Map.of(
                "finish_reason", name.isEmpty() ? "stop" : "tool_calls", "message", answer)))));
    }
}
