package neuralcloud.controlplane.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.master.Master;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatWorkerTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";

    /** 图像保持独立二进制部件，审计摘要不含数据；格式错误或字节超额在调用模型前拒绝。 */
    @Test
    public void sendsVerifiedImageBytesToGemini() throws IOException {
        var image = ChatImage.fromBase64(PNG, ChatImage.MAX_TOTAL_BYTES);
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var result = withWorker(200, requests, worker -> worker.describeImages("看图", List.of(image)), completed("可见图案"));
        assertEquals("可见图案", result);
        var parts = requests.getFirst().getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts");
        assertEquals("看图", parts.get(0).getAsJsonObject().get("text").getAsString());
        var inline = parts.get(1).getAsJsonObject().getAsJsonObject("inlineData");
        assertEquals("image/png", inline.get("mimeType").getAsString());
        assertEquals(PNG, inline.get("data").getAsString());
        assertEquals(64, image.evidence().get("sha256").toString().length());
        assertFalse(image.evidence().toString().contains(PNG));
        for (String bad : List.of("", "%%%", "dGV4dA=="))
            assertThrows(IOException.class, () -> ChatImage.fromBase64(bad, ChatImage.MAX_BYTES));
        assertThrows(IOException.class, () -> ChatImage.fromBase64(PNG, image.size()-1));
    }

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
        assertEquals("Gemini 文本候选未正常结束（MAX_TOKENS）", partial.getMessage());
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
    public void offersAuthoredHistoryOnlyWithABoundPrivateSpeaker() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        String result = completed("{\"kind\":\"SELF_HISTORY\",\"query\":\"摄影\"}");
        String question = "我以前说过摄影的什么事？";
        var turn = withWorker(200, requests, worker -> worker.planTurn(question, List.of(), true), result);
        assertEquals(ChatWorker.TurnKind.SELF_HISTORY, turn.kind());
        assertEquals("摄影", turn.query());
        assertTrue(requests.getFirst().getAsJsonObject("generationConfig").toString().contains("SELF_HISTORY"));
        assertThrows(IOException.class, () -> withWorker(200, requests, worker -> worker.planTurn(question), result));
        assertFalse(requests.getFirst().getAsJsonObject("generationConfig").toString().contains("SELF_HISTORY"));
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

    @Test
    public void supportsOpenAiChatForCloudAndLocalEndpoints() throws IOException {
        var requests = new CopyOnWriteArrayList<JsonObject>();
        var auth = new AtomicReference<String>();
        var replies = new CopyOnWriteArrayList<String>();
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(JsonParser.parseString(body).getAsJsonObject());
                String payload = replies.isEmpty() ? "" : replies.removeFirst();
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            var cloud = ChatWorker.openAiChat(client, endpoint, HttpClient.Version.HTTP_1_1, "deepseek-flash",
                    "ds-key", Duration.ofSeconds(3), 1024, false, Master.ThinkingFormat.DEEPSEEK,
                    "只写许可正文。", "喜欢摄影。");

            // 1. 规划轮次（工具调用 + token 计数）与带材料回复
            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                     "tool_calls":[{"id":"c1","type":"function","function":{"name":"plan_turn",
                     "arguments":"{\\"kind\\":\\"HISTORY\\",\\"query\\":\\"周三评审\\"}"}}]}}],
                     "usage":{"prompt_tokens":120,"prompt_tokens_details":{"cached_tokens":40}}}
                    """);
            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"stop","message":{"content":"周三下午三点开会。"}}]}
                    """);
            var turn = cloud.planTurn("周三评审会几点？", List.of(), true);
            assertEquals(ChatWorker.TurnKind.HISTORY, turn.kind());
            assertEquals("周三评审", turn.query());
            assertEquals(Map.of("promptTokenCount", 120, "cachedContentTokenCount", 40), turn.usage());
            assertEquals("Bearer ds-key", auth.get());
            assertEquals("disabled", requests.getFirst().getAsJsonObject("thinking").get("type").getAsString());
            assertEquals("plan_turn", requests.getFirst().getAsJsonObject("tool_choice")
                    .getAsJsonObject("function").get("name").getAsString());
            assertEquals(Optional.of("周三下午三点开会。"), cloud.replyToTurn(turn, List.of("周三下午三点开会")));
            assertTrue(requests.getLast().getAsJsonArray("messages").get(1).getAsJsonObject()
                    .get("content").getAsString().contains("记忆服务返回的查询材料"));

            // 2. 群聊参与判断与服务器状态开场选择
            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                     "tool_calls":[{"id":"c2","type":"function","function":{"name":"select_reply",
                     "arguments":"{\\"decision\\":\\"SKIP\\"}"}}]}}]}
                    """);
            assertEquals(Optional.empty(), cloud.reply(AMBIENT, APPROVED_TEXT));

            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                     "tool_calls":[{"id":"c3","type":"function","function":{"name":"select_status_intro",
                     "arguments":"{\\"intro\\":\\"刚查过这台服务器，结果在这儿。\\"}"}}]}}]}
                    """);
            var serverStatus = new ServerStatus("2026-11-23T00:00:00Z", 3600, 0.25, 1L << 30, 2L << 30, 4L << 30, 8L << 30);
            assertTrue(cloud.replyToServerStatus(DIRECT, serverStatus).orElseThrow()
                    .startsWith("刚查过这台服务器，结果在这儿。\n"));

            // 云端识图发送实际字节，文字端点继续沿用上面的纯字符串契约。
            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"stop","message":{"content":"可见图案"}}]}
                    """);
            var image = ChatImage.fromBase64(PNG, ChatImage.MAX_BYTES);
            assertEquals("可见图案", cloud.describeImages("比较两张", List.of(image, image)));
            var visual = requests.getLast().getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
            assertEquals(3, visual.size());
            assertEquals("比较两张", visual.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("data:image/png;base64," + PNG,
                    visual.get(2).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());

            // 3. 截断与 HTTP 错误不泄露上游错误正文
            replies.add("""
                    {"model":"deepseek-flash","choices":[{"finish_reason":"length","message":{"content":"截断"}}]}
                    """);
            var truncated = assertThrows(IOException.class, () -> cloud.reply(DIRECT, APPROVED_TEXT));
            assertEquals("Chat Worker 文本候选未正常结束（length）", truncated.getMessage());

            status.set(403);
            replies.add("secret upstream error body");
            var forbidden = assertThrows(IOException.class, () -> cloud.reply(DIRECT, APPROVED_TEXT));
            assertEquals("Chat Worker 返回 HTTP 403", forbidden.getMessage());

            // 4. 本机免鉴权 OpenAI 兼容端点（chat_template_kwargs，无 Authorization 头）
            status.set(200);
            requests.clear();
            replies.add("""
                    {"model":"local-qwen","choices":[{"finish_reason":"stop","message":{"content":"本机回复。"}}]}
                    """);
            var local = ChatWorker.openAiChat(client, endpoint, HttpClient.Version.HTTP_1_1, "local-qwen",
                    null, Duration.ofSeconds(3), 512, false, Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS,
                    "只写许可正文。", "喜欢摄影。");
            assertEquals(Optional.of("本机回复。"), local.reply(DIRECT, APPROVED_TEXT));
            assertEquals(null, auth.get());
            assertFalse(requests.getFirst().getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
            assertFalse(requests.getFirst().has("thinking"));
        } finally {
            server.stop(0);
        }
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
