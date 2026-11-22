package neuralcloud.controlplane.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
    public void routesBeforeGeneratingEveryReplyWithTheSamePersona() throws Exception {
        var route = new AtomicReference<>("CONVERSATION");
        var report = new AtomicReference<String>();
        var drafts = new AtomicInteger();
        var generation = new AtomicReference<JsonObject>();
        var draftedText = new AtomicReference<>("喜欢摄影。");
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
            generation.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            send(exchange, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":%s}]}}]}
                    """.formatted(new JsonPrimitive(draftedText.get())));
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
            assertTrue(systemInstruction(generation.get()).startsWith("你是小雾。"));
            assertEquals(List.of("chat-worker:chat_worker_router_request", "router:router_route"), events);
            assertEquals(Integer.valueOf(3), reply.causedBy());

            events.clear();
            route.set("ACTION_REQUEST");
            draftedText.set("这次我还没法查，手上也没有结果。");
            var action = entry.send("检查服务器当前状态。", trace);
            assertEquals("{route=ACTION_REQUEST}", tracedRoute.get());
            assertEquals(draftedText.get(), action.data().get("text"));
            assertTrue(systemInstruction(generation.get()).startsWith("你是小雾。"));
            assertEquals("我目前无法执行这项现场查询或操作。这一轮没有执行，也没有获得现场结果。",
                    generationInput(generation.get()));

            events.clear();
            route.set("UNSUPPORTED");
            draftedText.set("嗯，这个我没有权限，就没做。");
            var unsupported = entry.send("这是越权请求。", trace);
            assertEquals("{route=UNSUPPORTED}", tracedRoute.get());
            assertEquals(draftedText.get(), unsupported.data().get("text"));
            assertTrue(systemInstruction(generation.get()).startsWith("你是小雾。"));
            assertEquals("这个请求超出了我能处理的权限范围，我没有执行。", generationInput(generation.get()));

            events.clear();
            route.set("INVALID");
            assertThrows(IOException.class, () -> entry.send("你好。", trace));
            assertEquals(List.of("chat-worker:chat_worker_router_request"), events);
            assertEquals(3, drafts.get());
        } finally {
            backend.stop(0);
        }
    }

    private static String systemInstruction(JsonObject request) {
        return request.getAsJsonObject("systemInstruction").getAsJsonArray("parts").get(0)
                .getAsJsonObject().get("text").getAsString();
    }

    private static String generationInput(JsonObject request) {
        return request.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static void send(HttpExchange exchange, String json) throws IOException {
        try (exchange) {
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }
}
