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
import neuralcloud.controlplane.worker.DialogueMessage;
import neuralcloud.controlplane.worker.ServerStatus;
import neuralcloud.controlplane.worker.TaskWorker;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        var plan = new AtomicReference<>("{\"kind\":\"REPLY\",\"query\":\"\"}");
        var generation = new AtomicReference<JsonObject>();
        var draftedText = new AtomicReference<>("喜欢摄影。");
        var capability = new AtomicReference<>("read_server_status");
        var arguments = new AtomicReference<>("{}");
        var reads = new AtomicInteger();
        var events = new ArrayList<String>();
        var tracedRoute = new AtomicReference<String>();
        var trace = new Trace() {
            public void validUntil(java.time.Instant expiry) { throw new AssertionError("unexpected timed evidence"); }
            public java.time.Instant validUntil() { return java.time.Instant.MAX; }

            public java.util.Map<String, Long> sharedSources() { return java.util.Map.of(); }
            public int request() { return 1; }

            public Scope scope() { return new Scope("console", "test", "test-run"); }

            public List<DialogueMessage> dialogue() { return List.of(); }

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
            generation.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            boolean planning = generation.get().getAsJsonObject("generationConfig").has("responseSchema")
                    && "application/json".equals(generation.get().getAsJsonObject("generationConfig").get("responseMimeType").getAsString());
            if (!planning) drafts.incrementAndGet();
            send(exchange, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":%s}]}}]}
                    """.formatted(new JsonPrimitive(planning ? plan.get() : draftedText.get())));
        });
        backend.createContext("/deepseek", exchange -> {
            if (!"Bearer task-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            send(exchange, """
                    {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{
                    "tool_calls":[{"type":"function","function":{"name":%s,"arguments":%s}}]}}]}
                    """.formatted(new JsonPrimitive(capability.get()), new JsonPrimitive(arguments.get())));
        });
        backend.start();
        try (var client = HttpClient.newHttpClient()) {
            var base = URI.create("http://127.0.0.1:" + backend.getAddress().getPort());
            var settings = new Master.Settings(base.resolve("/v1/chat/completions"), "test-router",
                    HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(3), "probe", 8, 0, false);
            var router = new Router<>(new Master(client, settings), "选择流程。", Route.class);
            var chat = new ChatWorker(client, base.resolve("/generate"), Duration.ofSeconds(3), 1024,
                    "LOW", "test-key", "你是小雾。", "喜欢摄影。");
            var entry = ConsoleMain.conversation(chat, router, Optional.empty(), Optional.empty());

            var reply = entry.send("你喜欢什么？", trace);
            assertEquals("你喜欢什么？", report.get());
            assertEquals("喜欢摄影。", reply.data().get("text"));
            assertTrue(systemInstruction(generation.get()).startsWith("你是小雾。"));
            assertEquals(List.of("chat-worker:chat_worker_router_request", "router:router_route", "chat-worker:chat_worker_turn_plan"), events);
            assertEquals(Integer.valueOf(4), reply.causedBy());

            events.clear();
            plan.set("{\"kind\":\"SUBJECT_CONFLICT\",\"query\":\"\",\"conflict\":\"两项重要承诺无法同时履行\"}");
            assertThrows(IOException.class, () -> entry.send("两个重要约定只能兑现一个。", trace));
            assertEquals(1, drafts.get());

            events.clear();
            plan.set("{\"kind\":\"ACTION\",\"query\":\"\"}");
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

            var task = new TaskWorker(client, base.resolve("/deepseek"), "deepseek-flash", "task-key",
                    Duration.ofSeconds(3), () -> {
                        reads.incrementAndGet();
                        return new ServerStatus("2026-11-22T00:00:00Z", 7200, 0.5, 2L << 30, 4L << 30,
                                8L << 30, 16L << 30);
                    });
            var withTask = ConsoleMain.conversation(chat, router, Optional.of(task), Optional.empty());
            route.set("ACTION_REQUEST");
            events.clear();
            draftedText.set("嗯，我刚看了下这台服务器。");
            var status = withTask.send("检查服务器当前状态。", trace);
            assertEquals(List.of("chat-worker:chat_worker_router_request", "router:router_route", "chat-worker:chat_worker_turn_plan",
                    "task-worker:task_worker_dispatch", "task-worker:task_worker_result"), events);
            assertEquals(Integer.valueOf(6), status.causedBy());
            assertEquals("本轮已完成服务器状态读取。", generationInput(generation.get()));
            assertEquals("text/x.enum", generation.get().getAsJsonObject("generationConfig")
                    .get("responseMimeType").getAsString());
            assertEquals(draftedText.get() + "\n已运行 2.0 小时，一分钟负载 0.50。"
                    + "内存可用 2.0 / 4.0 GiB，根分区可用 8.0 / 16.0 GiB。", status.data().get("text"));
            assertTrue(systemInstruction(generation.get()).startsWith("你是小雾。"));

            events.clear();
            draftedText.set("咦，好像没看到结果呢。");
            assertThrows(IOException.class, () -> withTask.send("再检查一次服务器状态。", trace));
            assertEquals("task-worker:task_worker_result", events.getLast());

            capability.set("no_supported_action");
            events.clear();
            withTask.send("重启服务器。", trace);
            assertEquals("task-worker:task_worker_unavailable", events.getLast());
            assertEquals(2, reads.get());

            capability.set("read_server_status");
            arguments.set("{\"host\":\"another-host\"}");
            events.clear();
            assertThrows(IOException.class, () -> withTask.send("查另一台服务器。", trace));
            assertEquals("task-worker:task_worker_dispatch", events.getLast());
            assertEquals(2, reads.get());
            assertEquals(6, drafts.get());
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
