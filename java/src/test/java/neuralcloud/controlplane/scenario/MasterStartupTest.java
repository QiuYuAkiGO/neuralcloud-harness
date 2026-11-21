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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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

    private static void probe(String json, AtomicReference<JsonObject> request)
            throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/completions", exchange -> {
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
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/completions");
            var settings = new Master.Settings(endpoint, "test-master", HttpClient.Version.HTTP_1_1,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "继续数列", 12, 0.25);
            new Master(client, settings).probeStartup();
        } finally {
            server.stop(0);
        }
    }
}
