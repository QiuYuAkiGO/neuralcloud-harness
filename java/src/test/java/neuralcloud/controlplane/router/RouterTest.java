package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterIntent;
import neuralcloud.controlplane.orchestration.Orchestrator;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RouterTest {
    private static final String STEP = """
            {"id":"reply","worker":"CHAT_WORKER","text":"请转达已确认的安排","dependsOn":[]}""";
    private static final String PLAN = "{\"steps\":[" + STEP + "]}";
    private static final Orchestrator.Scope SCOPE = new Orchestrator.Scope("console:test", UUID.randomUUID());

    /** 模型提交的原始 JSON 要经过同一个严格边界，不能把无效步骤转成可执行指令。 */
    @Test
    public void rejectsPlansOutsideSingleChatStepContract() {
        for (String invalid : List.of("{}", "{\"steps\":[]}", "{\"steps\":[" + STEP + "," + STEP + "]}",
                PLAN.replace("CHAT_WORKER", "TASK_WORKER"), PLAN.replace("\"reply\"", "42"),
                PLAN.replace("\"reply\"", "\"  \""), PLAN.replace("请转达已确认的安排", " "),
                PLAN.replace("[]", "[\"other\"]"), PLAN.replace("\"text\":", "\"text\":null,\"text\":"),
                PLAN.replace("\"id\":", "\"scope\":\"other\",\"id\":"), PLAN.replace("\"steps\":", "\"scope\":{},\"steps\":"),
                PLAN + "{}", PLAN.substring(0, PLAN.length() - 2))) {
            assertTrue(invalid, assertThrows(IOException.class, () -> ChatPlan.parse(invalid, SCOPE))
                    .getMessage().contains("submit_plan.steps"));
        }
    }

    /** 无需回复时不发起推理；需要回复时只传批准的语义，且必须收到完整的原生工具回执。 */
    @Test
    public void usesOnlyAcceptedReplyIntentAndRequiresCompleteToolCall() throws Exception {
        var request = new AtomicReference<JsonObject>();
        var finish = new AtomicReference<>("tool_calls");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                String response = """
                        {"model":"plan-router","choices":[{"finish_reason":"%s","message":{"content":"",
                         "tool_calls":[{"id":"test-call","type":"function","function":{"name":"submit_plan","arguments":%s}}]}}]}
                        """.formatted(finish.get(), new Gson().toJson(PLAN));
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var settings = new Master.Settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions"), "plan-router", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "probe", 8, 0, false);
            var router = new Router<>(new Master(client, settings), "仅分类", Route.class);
            assertTrue(router.submitPlan(new MasterIntent("内部评价", "无需通知", List.of(), "", false), SCOPE).isEmpty());
            assertNull(request.get());
            var intent = new MasterIntent("内部评价", "请转达已确认的安排", List.of(), "", true);
            var submission = router.submitPlan(intent, SCOPE).orElseThrow();
            assertEquals("test-call", submission.toolCallId());
            assertEquals(SCOPE, submission.plan().scope());
            assertEquals(List.of(new Orchestrator.Step("reply", "CHAT_WORKER", intent.replyIntent(), List.of())),
                    submission.plan().steps());
            var sent = request.get();
            assertEquals("submit_plan", sent.getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").getAsString());
            assertEquals(1, sent.getAsJsonArray("tools").size());
            assertEquals(intent.replyIntent(), sent.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
            assertFalse(sent.toString().contains("内部评价"));
            finish.set("length");
            assertTrue(assertThrows(IOException.class, () -> router.submitPlan(intent, SCOPE)).getMessage().contains("完整"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void advertisesOnlyGeneralRoutesAndRejectsMeetingTestValue() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var payload = JsonParser.parseString(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                var declaration = payload.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function");
                var values = declaration.getAsJsonObject("parameters").getAsJsonObject("properties")
                        .getAsJsonObject("route").getAsJsonArray("enum");
                var choice = payload.getAsJsonObject("tool_choice");
                String prompt = payload.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                if (!"选择流程。\n只调用工具。".equals(prompt)
                        || !"select_route".equals(declaration.get("name").getAsString())
                        || choice == null || !"select_route".equals(
                                choice.getAsJsonObject("function").get("name").getAsString())
                        || !values.asList().stream().map(v -> v.getAsString()).toList()
                        .equals(List.of("CONVERSATION", "ACTION_REQUEST", "UNSUPPORTED"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                String route = calls.getAndIncrement() == 0 ? "CONVERSATION" : "MEETING_RESCHEDULE";
                byte[] response = """
                        {"model":"test-router","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                         "tool_calls":[{"id":"call-1","type":"function","function":{"name":"select_route",
                         "arguments":"{\\"route\\":\\"%s\\"}"}}]}}]}
                        """.formatted(route).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            var settings = new Master.Settings(endpoint, "test-router", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "probe", 8, 0, false);
            var router = new Router<>(new Master(client, settings), "选择流程。\r\n只调用工具。", Route.class);
            assertEquals(Route.CONVERSATION, router.route("请总结这段话。"));
            var error = assertThrows(IOException.class, () -> router.route("会议改好了吗？"));
            assertTrue(error.getMessage().contains("枚举契约"));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
