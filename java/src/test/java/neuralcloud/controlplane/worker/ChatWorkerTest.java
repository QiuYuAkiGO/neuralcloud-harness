package neuralcloud.controlplane.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.worker.ReplySelector.Addressing;
import neuralcloud.controlplane.worker.ReplySelector.Context;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatWorkerTest {
    private static final String APPROVED_TEXT = "会议已改到周六11时。";
    private static final Context DIRECT = Context.direct("会议的时间改好了吗？");
    private static final Context AMBIENT = new Context(Addressing.AMBIENT, "这个好看吗？", List.of("发了一张照片。"));

    @Test
    public void sendsApprovedTextAndReturnsOnlyVisibleDraft() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        assertEquals(Optional.of("会议改到周六11时了。"), invoke(200, DIRECT, requests, """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"thought":true,"text":"思考摘要"},{"text":"会议改到"},{"text":"周六11时了。"}]}}]}
                """));
        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals(APPROVED_TEXT, content(request));
        assertTrue(instructions(request).contains("只写许可正文。"));
        assertTrue(instructions(request).contains("只传达正文明确给出的内容"));
        assertFalse(request.has("tools"));
        assertFalse(request.toString().contains("test-api-key"));
    }

    @Test
    public void rejectsTruncatedDraftAndDoesNotExposeProviderErrorBody() {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var partial = assertThrows(IOException.class, () -> invoke(200, DIRECT, requests, """
                {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"会议已"}]}}]}
                """));
        assertTrue(partial.getMessage().contains("正常结束"));
        var invalidKey = assertThrows(IOException.class, () -> invoke(403, DIRECT, requests, "provider internal body"));
        assertEquals("Gemini 返回 HTTP 403", invalidKey.getMessage());
    }

    @Test
    public void skipsWithoutGeneratingContentAndRejectsUnknownDecision() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        assertEquals(Optional.empty(), invoke(200, AMBIENT, requests, completed("SKIP")));
        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertTrue(instructions(request).contains("喜欢摄影。"));
        assertTrue(content(request).contains("发了一张照片。"));
        assertFalse(content(request).contains(APPROVED_TEXT));
        assertEquals("text/x.enum", request.getAsJsonObject("generationConfig").get("responseMimeType").getAsString());

        var error = assertThrows(IOException.class, () -> invoke(200, AMBIENT, requests, completed("MAYBE")));
        assertTrue(error.getMessage().contains("REPLY 或 SKIP"));
        assertEquals(1, requests.size());
    }

    @Test
    public void generatesApprovedTextOnlyAfterAmbientReplyDecision() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        assertEquals(Optional.of("知道了。"), invoke(200, AMBIENT, requests, completed("REPLY"), completed("知道了。")));
        assertEquals(2, requests.size());
        assertEquals(APPROVED_TEXT, content(requests.get(1)));
        assertTrue(instructions(requests.get(1)).contains("只传达正文明确给出的内容"));
    }

    @Test
    public void requestsOwnIdentityWithoutSupplyingAnAccountOrSearchString() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var turn = withWorker(200, requests, worker -> worker.planTurn("你还记得我是谁吗？"),
                completed("{\"kind\":\"SELF_IDENTITY\",\"query\":\"\"}"));
        assertEquals(ChatWorker.TurnKind.SELF_IDENTITY, turn.kind());
        assertEquals("", turn.query());
        assertEquals("你还记得我是谁吗？", content(requests.getFirst()));
        assertThrows(IOException.class, () -> withWorker(200, requests,
                worker -> worker.planTurn("你还记得我是谁吗？"),
                completed("{\"kind\":\"SELF_IDENTITY\",\"query\":\"90001\"}")));
    }

    @Test
    public void keepsPreviousTurnAsPrefixForFollowUpAndRejectsPlaceholderQueries() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        String first = "你还记得我是谁吗？";
        String followUp = "要不再想想看";
        String identity = completed("{\"kind\":\"SELF_IDENTITY\",\"query\":\"\"}");
        var next = withWorker(200, requests, worker -> {
            worker.planTurn(first);
            return worker.planTurn(followUp, List.of(new DialogueMessage(DialogueMessage.Speaker.USER, first),
                    new DialogueMessage(DialogueMessage.Speaker.MISTY, "我想再查一下。")));
        }, identity, identity);
        assertEquals(ChatWorker.TurnKind.SELF_IDENTITY, next.kind());
        assertEquals("", next.query());
        var before = requests.getFirst().getAsJsonArray("contents");
        var after = requests.getLast().getAsJsonArray("contents");
        assertEquals(before.asList(), after.asList().subList(0, before.size()));
        assertEquals("model", after.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals(followUp, after.get(2).getAsJsonObject().getAsJsonArray("parts").get(0)
                .getAsJsonObject().get("text").getAsString());
        assertThrows(IOException.class, () -> withWorker(200, requests, worker -> worker.planTurn(followUp),
                completed("{\"kind\":\"HISTORY\",\"query\":\"上文内容\"}")));
    }

    private static String completed(String text) {
        return """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":%s}]}}]}
                """.formatted(new JsonPrimitive(text));
    }

    private static String content(JsonObject request) {
        return request.getAsJsonArray("contents").get(0).getAsJsonObject()
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static String instructions(JsonObject request) {
        return request.getAsJsonObject("systemInstruction").getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    private static Optional<String> invoke(int status, Context context, List<JsonObject> requests, String... responses)
            throws IOException {
        return withWorker(status, requests, worker -> worker.reply(context, APPROVED_TEXT), responses);
    }

    @FunctionalInterface
    private interface WorkerCall<T> {
        T apply(ChatWorker worker) throws IOException;
    }

    private static <T> T withWorker(int status, List<JsonObject> requests, WorkerCall<T> action, String... responses)
            throws IOException {
        requests.clear();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String path = "/v1beta/models/test:generateContent";
        server.createContext(path, exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int index = requests.size();
                requests.add(JsonParser.parseString(body).getAsJsonObject());
                boolean authenticated = "test-api-key".equals(exchange.getRequestHeaders().getFirst("x-goog-api-key"))
                        && exchange.getRequestURI().getRawQuery() == null && "POST".equals(exchange.getRequestMethod());
                byte[] response = (index < responses.length ? responses[index] : "").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(authenticated ? (index < responses.length ? status : 500) : 401,
                        response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
            return action.apply(new ChatWorker(client, endpoint, Duration.ofSeconds(3), 1024, "LOW", "test-api-key",
                    "只写许可正文。", "喜欢摄影。"));
        } finally {
            server.stop(0);
        }
    }
}
