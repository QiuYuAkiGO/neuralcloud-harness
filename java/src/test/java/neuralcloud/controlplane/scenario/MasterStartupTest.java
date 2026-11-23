package neuralcloud.controlplane.scenario;

import com.google.gson.JsonObject;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MasterStartupTest {
    @Test
    public void probesConfiguredModelThroughCompletions() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        probe("{\"model\":\"test-master\",\"choices\":[{\"text\":\" 4\"}]}", request);
        assertEquals("test-master", request.get().get("model").getAsString());
        assertEquals("继续数列", request.get().get("prompt").getAsString());
        assertEquals(12, request.get().get("max_tokens").getAsInt());
        assertEquals(0.25, request.get().get("temperature").getAsDouble(), 0);
    }

    @Test
    public void rejectsAnEmptyCompletionEvenWhenHttpSucceeds() {
        IOException error = assertThrows(IOException.class,
                () -> probe("{\"model\":\"test-master\",\"choices\":[{\"text\":\" \"}]}", new AtomicReference<>()));
        assertTrue(error.getMessage().contains("choices[0].text"));
    }

    @Test
    public void reportsTheSameContractErrorForInvalidCompletionShapes() {
        for (String fields : List.of("", ",\"choices\":{}", ",\"choices\":[]", ",\"choices\":[null]",
                ",\"choices\":[{}]", ",\"choices\":[{\"text\":1}]")) {
            String response = "{\"model\":\"test-master\"" + fields + "}";
            IOException error = assertThrows(IOException.class, () -> probe(response, new AtomicReference<>()));
            assertEquals("推理响应不符合 choices[0].text 字符串契约", error.getMessage());
        }
    }

    @Test
    public void sendsChatWithThinkingDisabledAndKeepsOnlyVisibleContent() throws Exception {
        var request = new AtomicReference<JsonObject>();
        var result = new AtomicReference<Master.Completion>();
        withEndpoint("""
                {"model":"test-master","choices":[{"message":{"content":"4","reasoning_content":"内部文本"}}]}
                """, request, false, master -> result.set(master.complete("继续数列", 12, 0.25, List.of())));
        var message = request.get().getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("user", message.get("role").getAsString());
        assertEquals("继续数列", message.get("content").getAsString());
        assertFalse(request.get().getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
        assertEquals("4", result.get().text());
        assertTrue(result.get().reasoningFieldPresent());
    }

    @Test
    public void preservesTheReasoningIndicatorWhenChatHasNoVisibleContent() throws Exception {
        withEndpoint("""
                {"model":"test-master","choices":[{"message":{"content":null,"reasoning_content":"内部文本"}}]}
                """, new AtomicReference<>(), false, master -> {
            var result = master.complete("继续数列", 12, 0.25, List.of());
            assertTrue(result.text().isEmpty());
            assertTrue(result.reasoningFieldPresent());
        });
    }

    @Test
    public void sendsBearerAuthAndCloudThinkingFormats() throws Exception {
        var authHeader = new AtomicReference<String>();
        var request = new AtomicReference<JsonObject>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                request.set(JsonParser.parseString(body).getAsJsonObject());
                byte[] response = """
                        {"model":"deepseek-v4-pro","choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            var deepseek = new Master.Settings(endpoint, "deepseek-v4-pro", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "OK", 16, 0, false,
                    "cloud-key", Master.ThinkingFormat.DEEPSEEK, null);
            assertEquals("OK", new Master(client, deepseek).complete("ping", 16, 0, List.of()).text());
            assertEquals("Bearer cloud-key", authHeader.get());
            assertEquals("disabled", request.get().getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(request.get().has("chat_template_kwargs"));

            var plain = new Master.Settings(endpoint, "deepseek-v4-pro", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "OK", 16, 0, false,
                    null, Master.ThinkingFormat.NONE, null);
            assertEquals("OK", new Master(client, plain).complete("ping", 16, 0, List.of()).text());
            assertEquals(null, authHeader.get());
            assertFalse(request.get().has("thinking"));
            assertFalse(request.get().has("chat_template_kwargs"));
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface MasterCall {
        void run(Master master) throws IOException, InterruptedException;
    }

    private static void probe(String json, AtomicReference<JsonObject> request)
            throws IOException, InterruptedException {
        withEndpoint(json, request, null, Master::probeStartup);
    }

    private static void withEndpoint(String json, AtomicReference<JsonObject> request, Boolean enableThinking, MasterCall call)
            throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String path = enableThinking == null ? "/v1/completions" : "/v1/chat/completions";
        server.createContext(path, exchange -> {
            try (exchange) {
                // 与本机推理服务一致，测试端点拒绝 HTTP/2 升级请求。
                if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                request.set(JsonParser.parseString(body).getAsJsonObject());
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
            var settings = new Master.Settings(endpoint, "test-master", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "继续数列", 12, 0.25, enableThinking);
            call.run(new Master(client, settings));
        } finally {
            server.stop(0);
        }
    }
}
