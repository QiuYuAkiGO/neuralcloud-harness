package neuralcloud.controlplane.app;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.worker.ChatWorker;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConsoleConversationTest {
    @Test
    public void routesBeforeReplyAndDoesNotGenerateForActionsOrRouterFailures() throws Exception {
        var route = new AtomicReference<>("CONVERSATION");
        var report = new AtomicReference<String>();
        var drafts = new AtomicInteger();
        var events = new ArrayList<String>();
        var tracedRoute = new AtomicReference<String>();
        var trace = new Trace() {
            public int request() { return 1; }

            public int write(Integer parent, String node, String type, Object data) {
                events.add(node + ":" + type);
                assertEquals(Integer.valueOf(events.size()), parent);
                if ("router".equals(node)) {
                    tracedRoute.set(data.toString());
                }
                return events.size() + 1;
            }
        };
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            report.set(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
            send(exchange, """
                    {"model":"test-router","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                    "tool_calls":[{"id":"call","type":"function","function":{"name":"select_route",
                    "arguments":"{\\"route\\":\\"%s\\"}"}}]}}]}
                    """.formatted(route.get()));
        });
        backend.createContext("/generate", exchange -> {
            drafts.incrementAndGet();
            send(exchange, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"喜欢摄影。"}]}}]}
                    """);
        });
        backend.start();
        try (var client = HttpClient.newHttpClient()) {
            var base = URI.create("http://127.0.0.1:" + backend.getAddress().getPort());
            var settings = new Master.Settings(base.resolve("/v1/chat/completions"), "test-router",
                    HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(3), "probe", 8, 0, false);
            var router = new Router<>(new Master(client, settings), "选择流程。", Route.class);
            var chat = new ChatWorker(client, base.resolve("/generate"), Duration.ofSeconds(3), 1024,
                    "LOW", "test-key", "你是小雾。", "喜欢摄影。");
            var entry = ConsoleMain.conversation(chat, router);

            var reply = entry.send("你喜欢什么？", trace);
            assertEquals("你喜欢什么？", report.get());
            assertEquals("喜欢摄影。", reply.data().get("text"));
            assertEquals(List.of("chat-worker:chat_worker_router_request", "router:router_route"), events);
            assertEquals(Integer.valueOf(3), reply.causedBy());

            events.clear();
            route.set("ACTION_REQUEST");
            var action = entry.send("检查服务器当前状态。", trace);
            assertEquals("{route=ACTION_REQUEST}", tracedRoute.get());
            assertTrue(action.data().get("text").toString().contains("我没有执行"));

            events.clear();
            route.set("INVALID");
            assertThrows(IOException.class, () -> entry.send("你好。", trace));
            assertEquals(List.of("chat-worker:chat_worker_router_request"), events);
            assertEquals(1, drafts.get());
        } finally {
            backend.stop(0);
        }
    }

    private static void send(HttpExchange exchange, String json) throws IOException {
        try (exchange) {
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }
}
