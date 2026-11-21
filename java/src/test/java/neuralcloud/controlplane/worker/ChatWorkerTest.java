package neuralcloud.controlplane.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatWorkerTest {
    @Test
    public void sendsApprovedTextAndReturnsOnlyVisibleDraft() throws IOException {
        var request = new AtomicReference<JsonObject>();
        assertEquals("会议改到周六11时了。", invoke(200, """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"thought":true,"text":"思考摘要"},{"text":"会议改到"},{"text":"周六11时了。"}]}}]}
                """, request));
        assertEquals("会议已改到周六11时。", request.get().getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("只写许可正文。", request.get().getAsJsonObject("systemInstruction")
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(request.get().has("tools"));
        assertFalse(request.get().toString().contains("test-api-key"));
    }

    @Test
    public void rejectsTruncatedDraftAndDoesNotExposeProviderErrorBody() {
        var request = new AtomicReference<JsonObject>();
        var partial = assertThrows(IOException.class, () -> invoke(200, """
                {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"会议已"}]}}]}
                """, request));
        assertTrue(partial.getMessage().contains("正常结束"));
        var invalidKey = assertThrows(IOException.class, () -> invoke(403, "provider internal body", request));
        assertEquals("Gemini 返回 HTTP 403", invalidKey.getMessage());
    }

    private static String invoke(int status, String json, AtomicReference<JsonObject> request) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String path = "/v1beta/models/test:generateContent";
        server.createContext(path, exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                request.set(JsonParser.parseString(body).getAsJsonObject());
                boolean authenticated = "test-api-key".equals(exchange.getRequestHeaders().getFirst("x-goog-api-key"))
                        && exchange.getRequestURI().getRawQuery() == null && "POST".equals(exchange.getRequestMethod());
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(authenticated ? status : 401, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
            return new ChatWorker(client, endpoint, Duration.ofSeconds(3), 1024, "LOW", "test-api-key",
                    "只写许可正文。").draftReply("会议已改到周六11时。");
        } finally {
            server.stop(0);
        }
    }
}
