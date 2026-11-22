package neuralcloud.controlplane.router;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.master.Master;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RouterTest {
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
