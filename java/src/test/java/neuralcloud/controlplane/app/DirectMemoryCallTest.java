package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.ConversationMemory;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.worker.TaskWorker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
        try (var client = HttpClient.newHttpClient(); var audit = AuditLog.open(folder.getRoot().toPath())) {
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
                    var reply = ConsoleMain.masterConversation(master, 128, 0, Optional.of(memory)).send("查巡检别名。", trace);
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
