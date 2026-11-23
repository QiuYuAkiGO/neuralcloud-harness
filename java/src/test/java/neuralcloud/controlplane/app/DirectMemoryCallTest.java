package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterRuntime;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DirectMemoryCallTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();

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
            var memory = new ConversationMemory(audit, new ResidentMemory(client, base, "misty", "test-token", true));
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
                            Optional.of(memory)).send("查巡检别名。", trace);
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
            var memory = new ConversationMemory(audit, new ResidentV2(client, base, "misty", "test-token", true, journal, null));
            var timeout = Duration.ofSeconds(3);
            var master = new Master(client, new Master.Settings(base.resolve("/model"), "master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            var trace = Trace.forChain(audit, "console", "intent", audit.write("intent", null, "master", "operator_message",
                    Map.of("text", "/intent 请核对周六的安排")));
            var reply = ConsoleMain.masterConversation(new MasterRuntime(master, epochs, 256, 0),
                    Optional.of(memory)).send("/intent 请核对周六的安排", trace);
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

    private static Object tool(String name, String arguments) {
        return Map.of("tool_calls", List.of(Map.of("id", "lookup-id", "type", "function",
                "function", Map.of("name", name, "arguments", arguments))));
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
