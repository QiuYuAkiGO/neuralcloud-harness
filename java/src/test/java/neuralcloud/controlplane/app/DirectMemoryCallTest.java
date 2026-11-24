package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterRuntime;
import neuralcloud.controlplane.router.RouterMemoryWriter;
import neuralcloud.controlplane.orchestration.MasterEpochs;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.memory.MemoryJournal;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.worker.TaskWorker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DirectMemoryCallTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final String SHARED_FACT = "验票地点是槐树厅";

    /** 共享持续开放时，同版原文仍可引用，下一轮首个模型输入不应报告依据失效。 */
    @Test
    public void stillVisibleSharedMemoryDoesNotInvalidateTheNextTurn() throws Exception {
        var nextTurn = nextTurnWithSharedMemory(true);
        assertFalse(nextTurn.getFirst().contains("原依据已失效"));
    }

    /** 只恢复受众隔离时，下一轮首个输入须通知失效，整轮模型输入都不能再使用上轮原文。 */
    @Test
    public void restoredIsolationInvalidatesSharedMemoryBeforeTheNextModelInput() throws Exception {
        var nextTurn = nextTurnWithSharedMemory(false);
        assertTrue("首个模型输入应提示原依据失效", nextTurn.getFirst().contains("原依据已失效"));
        assertFalse("本轮模型输入含其他会话的旧原文", nextTurn.stream().anyMatch(input -> input.contains(SHARED_FACT)));
    }

    /** 两轮共用同一常驻 Memory 快照。第一轮通过正式工具交付其他会话的普通发言，然后只调整受众判定。 */
    private List<String> nextTurnWithSharedMemory(boolean stillVisible) throws Exception {
        String stream = "95fe56c7-0e74-4509-896d-e78ae6d5b42a";
        String id = "a".repeat(64);
        var ref = Map.of("record_id", id, "revision", 1);
        var item = Map.ofEntries(Map.entry("ref", ref), Map.entry("source_refs", List.of(ref)),
                Map.entry("status", "ONLINE"), Map.entry("kind", "observation"), Map.entry("basis", "direct"),
                Map.entry("view", "L2"), Map.entry("text", SHARED_FACT), Map.entry("occurred_at", "2026-11-18T09:00:00Z"),
                Map.entry("source_venue", "c2c:onebot:10001:10002"), Map.entry("citation_scope", "console:reader"),
                Map.entry("grant_dependent", true));
        var visible = new AtomicBoolean(true);
        var prompts = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/health", exchange -> respond(exchange, JSON.toJson(Map.of("api_major", 2,
                "status", "TEXT_READY", "character_id", "misty", "stream_id", stream, "head_seq", 1,
                "capabilities", List.of("durable_ingest", "read_refs", "scoped_recall", "changes", "self_identity",
                        "self_history", "assertions", "candidates", "corrections", "audience_reads", "audience_cards")))));
        server.createContext("/v2/changes", exchange -> {
            // 原文在首轮前已入账，次轮没有记录或授权事件可供消费者据此触发重读。
            boolean fromStart = List.of(exchange.getRequestURI().getQuery().split("&")).contains("after=0");
            var changes = fromStart ? List.of(Map.of("seq", 1, "op", "INGEST", "record_id", id, "revision", 1)) : List.of();
            respond(exchange, JSON.toJson(Map.of("stream_id", stream, "through", 1, "next_after", 1, "items", changes)));
        });
        server.createContext("/v2/recall", exchange -> {
            boolean allowed = visible.get() && forSharingAudience(body(exchange));
            respond(exchange, JSON.toJson(Map.of("stream_id", stream, "read_seq", 1, "items", allowed ? List.of(item) : List.of(),
                    "assessment", Map.of("state", allowed ? "DIRECT" : "NOT_FOUND", "sufficient", allowed, "absence_confirmed", false))));
        });
        server.createContext("/v2/read", exchange -> {
            var request = body(exchange);
            boolean allowed = visible.get() && forSharingAudience(request);
            var items = new ArrayList<Map<String, ?>>();
            for (var value : request.getAsJsonArray("targets")) {
                var target = value.getAsJsonObject();
                String requested = target.get("record_id").getAsString();
                boolean matches = id.equals(requested) && (!target.has("revision") || target.get("revision").getAsLong() == 1);
                items.add(allowed && matches ? item : Map.of("record_id", requested, "status", "NOT_FOUND"));
            }
            respond(exchange, JSON.toJson(Map.of("stream_id", stream, "read_seq", 1, "items", items)));
        });
        server.createContext("/model", exchange -> {
            String input = body(exchange).getAsJsonArray("messages").toString();
            boolean lookup = prompts.isEmpty();
            prompts.add(input);
            Object answer = lookup ? tool("memory_call", "{\"kind\":\"HISTORY\",\"query\":\"验票\"}")
                    : Map.of("content", input.contains(SHARED_FACT) ? SHARED_FACT : "收到");
            respond(exchange, JSON.toJson(Map.of("model", "master", "choices", List.of(Map.of(
                    "finish_reason", lookup ? "tool_calls" : "stop", "message", answer)))));
        });
        server.start();
        var directory = folder.getRoot().toPath();
        try (var http = HttpClient.newHttpClient(); var audit = AuditLog.open(directory);
             var epochs = new MasterEpochs(directory.resolve("epochs.sqlite"), MasterEpochs.Profile.DEFAULT);
             var journal = new MemoryJournal(directory.resolve("journal.sqlite"))) {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var resident = new ResidentV2(http, base, "misty", "fixture", true, journal, null);
            resident.verify(false);
            var access = new ConsoleMain.MemoryAccess(new ConversationMemory(audit, resident),
                    new RouterMemoryWriter(audit, resident), Optional.empty());
            var timeout = Duration.ofSeconds(3);
            var master = new Master(http, new Master.Settings(base.resolve("/model"), "master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            var entry = ConsoleMain.masterConversation(new MasterRuntime(master, epochs, 256, 0), Optional.of(access));
            sendReaderTurn(entry, audit, "请查一下验票地点。");
            assertTrue("上轮工具须将可引用原文送入模型", prompts.getLast().contains(SHARED_FACT));
            int firstNextInput = prompts.size();
            visible.set(stillVisible);
            sendReaderTurn(entry, audit, "继续。");
            return List.copyOf(prompts.subList(firstNextInput, prompts.size()));
        } finally {
            server.stop(0);
        }
    }

    /** 回环 Memory 只向以该角色和目标会话请求 CITE 的消费者提供跨会话引文。 */
    private static boolean forSharingAudience(JsonObject request) {
        var context = request.getAsJsonObject("context");
        return "misty".equals(context.get("character_id").getAsString())
                && "console:reader".equals(context.get("conversation_id").getAsString())
                && request.has("use") && "CITE".equals(request.get("use").getAsString());
    }

    /** 像控制台一样给每次入站分配事件标识，交由同一处理器刷新模型输入。 */
    private static void sendReaderTurn(ConsoleServer.Exchange entry, AuditLog audit, String question) throws Exception {
        int request = audit.write("reader", null, "master", "operator_message", Map.of("text", question));
        entry.send(question, Trace.forChain(audit, "console", "reader", request));
    }

    @Test
    public void masterAndTaskReadDirectlyAndUseTheAuditedResultInTheirNextModelCall() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var searches = new CopyOnWriteArrayList<JsonObject>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            var body = body(exchange);
            requests.add(body);
            String model = body.get("model").getAsString();
            boolean beforeRead = body.getAsJsonArray("messages").size() == 2;
            Object message = beforeRead ? tool("memory_call", "{\"kind\":\"HISTORY\",\"query\":\"巡检别名\"}")
                    : "master".equals(model) ? Map.of("content", "巡检别名指外部主机。") : tool("no_supported_action", "{}");
            String finish = !beforeRead && "master".equals(model) ? "stop" : "tool_calls";
            respond(exchange, JSON.toJson(Map.of("model", model, "choices", List.of(Map.of("message", message, "finish_reason", finish)))));
        });
        server.createContext("/v1/recall", exchange -> {
            searches.add(body(exchange));
            respond(exchange, """
                    {"status":"OK","items":[{"status":"ONLINE","record_id":"older","revision":1,"kind":"observation",
                    "content":"巡检别名指外部主机。","occurred_at":5,"source":{"kind":"observation","privacy":"private",
                    "character_id":"misty","source_instance":"neuralcloud-java","session_id":"console:s","conversation_id":"console:s"}}]}
                    """);
        });
        server.start();
        try (var client = HttpClient.newHttpClient(); var audit = AuditLog.open(folder.getRoot().toPath());
             var epochs = new MasterEpochs(audit.directory().resolve("epochs.sqlite"), MasterEpochs.Profile.DEFAULT)) {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var resident = new ResidentMemory(client, base, "misty", "test-token", true);
            var memory = new ConversationMemory(audit, resident);
            var timeout = Duration.ofSeconds(3);
            var master = new Master(client, new Master.Settings(base.resolve("/model"), "master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            var task = new TaskWorker(client, base.resolve("/model"), "task", "task-key", timeout,
                    () -> { throw new AssertionError("外部主机不得读取本机状态"); });
            for (String node : List.of("master", "task-worker")) {
                requests.clear();
                int parent = audit.write("s", null, node, "input", Map.of("text", "先查巡检别名。"));
                var trace = Trace.forChain(audit, "console", "s", parent);
                int resultId;
                if ("master".equals(node)) {
                    var reply = ConsoleMain.masterConversation(new MasterRuntime(master, epochs, 128, 0),
                            Optional.of(new ConsoleMain.MemoryAccess(memory, new RouterMemoryWriter(audit, resident), Optional.empty()))).send("查巡检别名。", trace);
                    assertEquals("巡检别名指外部主机。", reply.data().get("text"));
                    resultId = reply.causedBy();
                } else {
                    var read = memory.bind(trace, MemoryCall.Caller.TASK_WORKER, parent);
                    assertTrue(task.execute("检查巡检别名对应的主机。", Optional.of(read)).isEmpty());
                    resultId = read.eventId();
                }
                var result = audit.event(trace.scope(), resultId);
                var lookup = audit.event(trace.scope(), result.causedBy());
                var request = audit.event(trace.scope(), lookup.causedBy());
                assertEquals(List.of("memory_call", "memory_recall", "memory_call_result"),
                        List.of(request.type(), lookup.type(), result.type()));
                assertEquals(List.of(node, "memory", "memory"), List.of(request.node(), lookup.node(), result.node()));
                assertEquals(parent, request.causedBy().intValue());
                assertEquals(2, requests.size());
                assertTrue(requests.getFirst().getAsJsonArray("tools").toString().contains("\"memory_call\""));
                assertFalse(requests.getFirst().getAsJsonArray("tools").toString().contains("SELF_HISTORY"));
                var messages = requests.getLast().getAsJsonArray("messages");
                var assistant = messages.get(2).getAsJsonObject();
                var reply = messages.get(3).getAsJsonObject();
                assertEquals("assistant", assistant.get("role").getAsString());
                assertEquals("lookup-id", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
                assertEquals("tool", reply.get("role").getAsString());
                assertEquals("lookup-id", reply.get("tool_call_id").getAsString());
                var sentences = result.data().getAsJsonObject().getAsJsonArray("sentences");
                assertEquals(sentences.get(0).getAsString(), reply.get("content").getAsString());
                assertEquals(1, result.data().getAsJsonObject().get("count").getAsInt());
            }
            // 即使模型自行添加目标账号或选择渠道未授权的范围，也不能产生下游检索。
            var trace = Trace.forChain(audit, "console", "s", audit.write("s", null, "master", "input", Map.of()));
            var read = memory.bind(trace, MemoryCall.Caller.MASTER, trace.request());
            for (String invalid : List.of("{\"kind\":\"HISTORY\",\"query\":\"巡检\",\"account_id\":\"10002\"}",
                    "{\"kind\":\"SELF_HISTORY\",\"query\":\"巡检\"}", "{\"kind\":\"HISTORY\",\"query\":\"上文内容\"}")) {
                assertThrows(IOException.class, () -> read.appendToolResult(new ArrayList<>(), "id", invalid, ""));
            }
            assertEquals(2, searches.size());
            for (var search : searches) {
                assertEquals("巡检别名", search.get("query").getAsString());
                assertEquals("console:s", search.getAsJsonObject("constraints").get("conversation_id").getAsString());
            }
        } finally {
            server.stop(0);
        }
    }

    /** 经正式 Master 处理器复核补交路径。范围提示不占序号，接受的版本必须对应模型实收的那条原话。 */
    @Test
    public void masterIntentUsesDeliveredNumbersAndCorrectsAnInvalidCitationOnce() throws Exception {
        var ref = Map.of("record_id", "a".repeat(64), "revision", 3);
        var item = Map.of("ref", ref, "source_refs", List.of(ref), "status", "ONLINE", "citation_scope", "console:intent",
                "text", "我周六要去看展。", "kind", "observation", "speaker", "小林", "occurred_at", "2026-11-18T09:00:00Z");
        var snapshot = Map.of("stream_id", "test-stream", "read_seq", 5, "items", List.of(item),
                "assessment", Map.of("state", "CONFLICT"));
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/recall", exchange -> respond(exchange, JSON.toJson(snapshot)));
        server.createContext("/v2/read", exchange -> respond(exchange, JSON.toJson(snapshot)));
        server.createContext("/model", exchange -> {
            var request = body(exchange);
            requests.add(request);
            var choice = request.get("tool_choice");
            String name = choice.isJsonPrimitive() ? "memory_call"
                    : choice.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
            String args = "memory_call".equals(name) ? "{\"kind\":\"HISTORY\",\"query\":\"看展\"}"
                    : JSON.toJson(Map.of("assessment", "根据小林的原话，我目前倾向于周六看展", "replyIntent", "说明看展安排",
                            "evidence", List.of(requests.size() == 2 ? 2 : 1), "impression", "", "needsReply", true));
            respond(exchange, JSON.toJson(Map.of("model", "master", "choices", List.of(Map.of(
                    "message", tool(name, args), "finish_reason", "tool_calls")))));
        });
        server.start();
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var dir = folder.getRoot().toPath();
        try (var client = HttpClient.newHttpClient(); var audit = AuditLog.open(dir);
             var epochs = new MasterEpochs(dir.resolve("epochs.sqlite"), MasterEpochs.Profile.DEFAULT);
             var journal = new MemoryJournal(dir.resolve("journal.sqlite"))) {
            var resident = new ResidentV2(client, base, "misty", "test-token", true, journal, null);
            var memory = new ConversationMemory(audit, resident);
            var timeout = Duration.ofSeconds(3);
            var master = new Master(client, new Master.Settings(base.resolve("/model"), "master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            var trace = Trace.forChain(audit, "console", "intent", audit.write("intent", null, "master", "operator_message",
                    Map.of("text", "/intent 请核对周六的安排")));
            var reply = ConsoleMain.masterConversation(new MasterRuntime(master, epochs, 256, 0),
                    Optional.of(new ConsoleMain.MemoryAccess(memory, new RouterMemoryWriter(audit, resident), Optional.empty()))).send("/intent 请核对周六的安排", trace);
            assertEquals("intent", reply.type());
            assertEquals(List.of(new MemoryCall.EvidenceRef("a".repeat(64), 3)), reply.data().get("evidence"));
            assertEquals(3, requests.size());
            var result = audit.event(trace.scope(), reply.causedBy());
            assertEquals("memory_call_result", result.type());
            var sentences = result.data().getAsJsonObject().getAsJsonArray("sentences");
            assertEquals("当前证据存在冲突。", sentences.get(0).getAsString());
            assertTrue(sentences.get(1).getAsString().startsWith("【证据 1】"));
            String delivered = requests.get(1).getAsJsonArray("messages").get(3).getAsJsonObject().get("content").getAsString();
            assertEquals(sentences.get(0).getAsString() + "\n" + sentences.get(1).getAsString(), delivered);
            var correction = requests.get(2).getAsJsonArray("messages").get(5).getAsJsonObject();
            assertEquals("lookup-id", correction.get("tool_call_id").getAsString());
            assertTrue(correction.get("content").getAsString().contains("evidence"));
        } finally {
            server.stop(0);
        }
    }

    /** 群问候直接提交意图。即使模型伪造身份查询或提交空历史词，读取边界也须在取证前拒绝。 */
    @Test
    public void groupGreetingCanSubmitIntentWithoutARecall() throws Exception {
        String message = " 可以和大家打声招呼吗";
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            requests.add(body(exchange));
            var intent = Map.of("assessment", "根据当前发言，我目前倾向于向群友问好", "replyIntent", "向大家问好",
                    "needsReply", true, "evidence", List.of(), "impression", "");
            respond(exchange, JSON.toJson(Map.of("model", "master", "choices", List.of(Map.of("finish_reason",
                    "tool_calls", "message", tool("submit_intent", JSON.toJson(intent)))))));
        });
        server.start();
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var dir = folder.getRoot().toPath();
        try (var client = HttpClient.newHttpClient(); var audit = AuditLog.open(dir);
             var epochs = new MasterEpochs(dir.resolve("epochs.sqlite"), MasterEpochs.Profile.DEFAULT);
             var journal = new MemoryJournal(dir.resolve("journal.sqlite"))) {
            var memory = new ConversationMemory(audit, new ResidentV2(client, base, "misty", "test-token", true, journal, null));
            var timeout = Duration.ofSeconds(3);
            var master = new Master(client, new Master.Settings(base.resolve("/model"), "master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            int routed = audit.write("group", null, "router", "router_route", Map.of("route", "CONVERSATION"));
            var trace = Trace.forQqGroup(audit, "group", routed, new Trace.QqGroup(10001, 20001, 10002, true));
            var read = memory.bindMasterIntent(trace, routed).orElseThrow();
            var runtime = new MasterRuntime(master, epochs, 256, 0);
            var admission = runtime.intent(message, trace, read, true, true);
            var intent = admission.value();
            runtime.workerReturned(trace, admission.matter(), Map.of("text", "大家好"));
            assertTrue(intent.needsReply());
            assertTrue(intent.evidence().isEmpty());
            assertEquals(routed, read.eventId());
            assertEquals(1, requests.size());
            var first = requests.getFirst();
            assertEquals("required", first.get("tool_choice").getAsString());
            var tools = first.getAsJsonArray("tools").asList().stream()
                    .map(t -> t.getAsJsonObject().getAsJsonObject("function")).toList();
            assertEquals(List.of("submit_intent", "memory_call"), tools.stream().map(t -> t.get("name").getAsString()).toList());
            var props = tools.getLast().getAsJsonObject("parameters").getAsJsonObject("properties");
            assertEquals(JSON.toJsonTree(List.of("HISTORY")), props.getAsJsonObject("kind").get("enum"));
            assertEquals(1, props.getAsJsonObject("query").get("minLength").getAsInt());
            for (String kind : List.of("SELF_IDENTITY", "HISTORY")) {
                var args = JSON.toJson(Map.of("kind", kind, "query", ""));
                var error = assertThrows(IOException.class, () -> read.appendToolResult(new ArrayList<>(), "id", args, ""));
                assertTrue(error.getMessage().contains("kind 或 query"));
            }
            assertEquals(1, audit.events("group").size());
        } finally {
            server.stop(0);
        }
        // 已验收意图与 Worker 回执先于后续派发落盘，完整内容不混入可重放的受众标签。
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("epochs.sqlite"));
             var sql = db.createStatement();
             var rows = sql.executeQuery("SELECT kind,body,checkpoint FROM segment WHERE checkpoint IS NOT NULL ORDER BY seq")) {
            assertTrue(rows.next());
            assertEquals("DECISION", rows.getString(1));
            assertFalse(rows.getString(2).contains("向大家问好"));
            assertEquals("向大家问好", JsonParser.parseString(rows.getString(3)).getAsJsonObject().get("replyIntent").getAsString());
            assertTrue(rows.next());
            assertEquals("RECEIPT", rows.getString(1));
            assertEquals("大家好", JsonParser.parseString(rows.getString(3)).getAsJsonObject().get("text").getAsString());
            assertFalse(rows.next());
        }
    }

    /** 私聊的身份参数只来自已引用的 SELF_IDENTITY 版本，历史查询里相同的正文不构成身份证据。 */
    @Test
    public void onlyCitedSenderIdentityBecomesTheWorkersConfirmedName() throws Exception {
        var evidence = new MemoryCall.EvidenceRef("a".repeat(64), 2);
        var item = Map.of("ref", evidence, "source_refs", List.of(evidence), "status", "ONLINE",
                "citation_scope", "c2c:onebot:10001:10002", "text", "青禾", "kind", "observation",
                "speaker", "用户", "occurred_at", "2026-11-18T09:00:00Z");
        var snapshot = Map.of("stream_id", "test-stream", "read_seq", 4, "items", List.of(item),
                "assessment", Map.of("state", "DIRECT"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/recall", exchange -> respond(exchange, JSON.toJson(snapshot)));
        server.start();
        var dir = folder.getRoot().toPath();
        try (var client = HttpClient.newHttpClient(); var audit = AuditLog.open(dir);
             var journal = new MemoryJournal(dir.resolve("journal.sqlite"))) {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var memory = new ConversationMemory(audit, new ResidentV2(client, base, "misty", "token", true, journal, null));
            int inbound = audit.write("identity", null, "openqq", "qq_message", Map.of("text", "你和我怎么称呼？"));
            var trace = Trace.forQqPrivate(audit, "identity", inbound, 10001, 10002);
            var read = memory.bindMasterIntent(trace, inbound).orElseThrow();
            var result = read.read(ConversationMemory.QueryKind.SELF_IDENTITY, "");
            assertEquals(List.of("【证据 1】当前 QQ 私聊的发件人（正在与 Chat Worker 对话的用户）经确认的称呼是「青禾」。"),
                    result.sentences());
            assertEquals(Optional.of("青禾"), read.confirmedSenderName(List.of(evidence)));
            assertTrue(read.confirmedSenderName(List.of()).isEmpty());
            assertTrue(read.confirmedSenderName(List.of(new MemoryCall.EvidenceRef(evidence.recordId(), 1))).isEmpty());
            var history = memory.bindMasterIntent(trace, inbound).orElseThrow();
            history.read(ConversationMemory.QueryKind.HISTORY, "称呼");
            assertTrue(history.confirmedSenderName(List.of(evidence)).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    static Object tool(String name, String arguments) {
        return Map.of("tool_calls", List.of(Map.of("id", "lookup-id", "type", "function",
                "function", Map.of("name", name, "arguments", arguments))));
    }

    static JsonObject body(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void respond(HttpExchange exchange, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
