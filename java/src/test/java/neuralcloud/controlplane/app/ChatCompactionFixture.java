package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 从正式模型配置和会话入口重放。仅模型 HTTP 对端受控，共享审计和 Trace 均由 Harness 创建。 */
final class ChatCompactionFixture implements AutoCloseable {
    static final String PERSONA = "你是小雾，正在和对方聊天。";
    private static final Gson JSON = new Gson();

    record Message(String role, String text) {}

    /** 保留原始请求。messages 只解开协议外壳，方便按消息角色、位置和正文逐项核对。 */
    record Call(String name, JsonObject body) {
        List<Message> messages() {
            var result = new ArrayList<Message>();
            if (body.has("messages")) {
                for (var raw : body.getAsJsonArray("messages")) {
                    var message = raw.getAsJsonObject();
                    result.add(new Message(message.get("role").getAsString(), message.get("content").getAsString()));
                }
            } else {
                result.add(new Message("system", parts(body.getAsJsonObject("systemInstruction"))));
                for (var raw : body.getAsJsonArray("contents")) {
                    var content = raw.getAsJsonObject();
                    result.add(new Message(content.get("role").getAsString(), parts(content)));
                }
            }
            return List.copyOf(result);
        }

        JsonArray entries() {
            return JsonParser.parseString(messages().getLast().text()).getAsJsonObject().getAsJsonArray("entries");
        }

        private static String parts(JsonObject content) {
            var text = new StringBuilder();
            for (var part : content.getAsJsonArray("parts")) text.append(part.getAsJsonObject().get("text").getAsString());
            return text.toString();
        }
    }

    @FunctionalInterface
    interface Handler {
        String respond(Call call) throws IOException, InterruptedException;
    }

    final AuditLog audit;
    final ChatWorker chat;
    final Router<Route> router;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile Optional<ConsoleMain.MemoryAccess> memory = Optional.empty();
    volatile Handler route = call -> "CONVERSATION";
    volatile Handler plan = call -> "{\"kind\":\"REPLY\",\"query\":\"\"}";
    volatile Handler reply = call -> "我看到了。";
    volatile Handler summary = call -> "{\"items\":[],\"closed\":[]}";
    volatile Handler participation = call -> "REPLY";
    volatile Handler statusIntro = call -> "嗯，我刚看了下这台服务器。";
    private final HttpServer server;
    private final ExecutorService requests;
    private final HttpClient http;

    ChatCompactionFixture(Path directory, ApiFormat format, String properties) throws IOException {
        this(directory, format, properties, 5);
    }

    ChatCompactionFixture(Path directory, ApiFormat format, String properties, int timeoutSeconds) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        requests = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(requests);
        server.createContext("/", this::respond);
        server.start();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try {
            var settings = config(directory, format, local, timeoutSeconds, properties);
            var transport = format == ApiFormat.GEMINI ? new GeminiLoopback(http, local.resolve("/generate")) : http;
            chat = settings.create(transport, settings.apiKey(key -> "fixture-key"), PERSONA);
            var timeout = Duration.ofSeconds(timeoutSeconds);
            var model = new Master(http, new Master.Settings(local.resolve("/router/chat/completions"), "trial",
                    HttpClient.Version.HTTP_1_1, timeout, timeout, "探测", 8, 0, false));
            router = new Router<>(model, "选择流程。", Route.class);
            audit = AuditLog.open(directory);
        } catch (IOException | RuntimeException error) {
            server.stop(0);
            requests.close();
            http.close();
            throw error;
        }
    }

    /** 配置验收与实际运行使用相同属性文件。试验密钥只由注入的环境读取函数提供。 */
    static ChatWorkerConfig.Settings config(Path directory, ApiFormat format, URI local, int timeout, String properties)
            throws IOException {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "model-", ".properties");
        String provider = format == ApiFormat.GEMINI ? """
                endpoint=https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent
                apiKeyEnv=GEMINI_API_KEY
                thinkingLevel=LOW
                """ : """
                apiFormat=OPENAI_CHAT
                endpoint=%s/chat/completions
                model=trial
                enableThinking=false
                """.formatted(local);
        try {
            Files.writeString(file, provider + """
                    httpVersion=HTTP_1_1
                    connectTimeoutSeconds=5
                    requestTimeoutSeconds=%d
                    maxOutputTokens=1024
                    replyPreferences=classpath:/config/chat-worker-misty-reply-preferences.txt
                    """.formatted(timeout) + properties);
            return ChatWorkerConfig.load(ConfigSource.file(file));
        } finally {
            Files.delete(file);
        }
    }

    List<Call> calls(String name) {
        return calls.stream().filter(call -> name.equals(call.name())).toList();
    }

    int inbound(String chain, String text) throws IOException {
        return audit.write(chain, null, "chat-worker", "operator_message", Map.of("text", text));
    }

    ConsoleServer.Exchange entry(Optional<ConsoleMain.MemoryAccess> access) {
        return ConsoleMain.conversation(chat, router, Optional.empty(), access);
    }

    /** 从网页消息入站到成功写出。返回正文；生成失败不会登记送达。Memory 按同一受众复核和记账。 */
    String turn(String chain, String question) throws Exception {
        var access = memory;
        var trace = Trace.forChain(audit, "console", chain, inbound(chain, question));
        var response = entry(access).send(question, trace);
        int draft = trace.write(response.causedBy(), "chat-worker", response.type(), response.data());
        String answer = (String) response.data().get("text");
        if (access.isPresent()) access.get().writes().verifyDelivery(trace);
        int delivered = trace.write(draft, "console", "console_response_written", trace.delivery(Map.of()));
        if (access.isPresent()) access.get().writes().rememberDelivered(trace, delivered, answer);
        return answer;
    }

    /** 以供应商会收到的请求决定返回哪种协议响应。网络阻塞只占用该请求的线程。 */
    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            var call = new Call(operation(body), body);
            calls.add(call);
            try {
                String text = switch (call.name()) {
                    case "select_route" -> JSON.toJson(Map.of("route", route.respond(call)));
                    case "plan_turn" -> plan.respond(call);
                    case "submit_chat_summary" -> summary.respond(call);
                    case "select_reply" -> participation.respond(call);
                    case "select_status_intro" -> statusIntro.respond(call);
                    case "reply" -> reply.respond(call);
                    default -> throw new AssertionError("测试供应商不支持 " + call.name());
                };
                write(exchange, 200, body.has("contents") ? gemini(text) : openAi(call.name(), text));
            } catch (ProviderError error) {
                write(exchange, error.status, "受控的供应商错误");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 由测试模型按已收到的内容判定窗口超限，客户端面对真实 HTTP 错误响应。 */
    static final class ProviderError extends IOException {
        final int status;
        ProviderError(int status) { this.status = status; }
    }

    private static String operation(JsonObject body) {
        if (body.has("messages")) {
            if (!body.has("tools")) return "reply";
            return body.getAsJsonArray("tools").get(0).getAsJsonObject()
                    .getAsJsonObject("function").get("name").getAsString();
        }
        var generation = body.getAsJsonObject("generationConfig");
        JsonElement schema = generation.has("responseSchema") ? generation.get("responseSchema") : generation.get("responseJsonSchema");
        if (schema == null) return "reply";
        var definition = schema.getAsJsonObject();
        if (definition.has("enum")) {
            return definition.getAsJsonArray("enum").asList().stream().anyMatch(value -> "SKIP".equals(value.getAsString()))
                    ? "select_reply" : "select_status_intro";
        }
        return definition.getAsJsonObject("properties").has("items") ? "submit_chat_summary" : "plan_turn";
    }

    private static String gemini(String text) {
        return JSON.toJson(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
                "content", Map.of("parts", List.of(Map.of("text", text))))),
                "usageMetadata", Map.of("promptTokenCount", 45, "cachedContentTokenCount", 6, "candidatesTokenCount", 12)));
    }

    private static String openAi(String name, String text) {
        String arguments = switch (name) {
            case "select_reply" -> JSON.toJson(Map.of("decision", text));
            case "select_status_intro" -> JSON.toJson(Map.of("intro", text));
            default -> text;
        };
        boolean plain = "reply".equals(name);
        var message = plain ? Map.of("content", text) : Map.of("tool_calls", List.of(Map.of(
                "id", "call-1", "type", "function", "function", Map.of("name", name, "arguments", arguments))));
        return JSON.toJson(Map.of("model", "trial", "choices", List.of(Map.of("finish_reason", plain ? "stop" : "tool_calls",
                "message", message)), "usage", Map.of("prompt_tokens", 45, "completion_tokens", 12,
                "prompt_tokens_details", Map.of("cached_tokens", 6))));
    }

    private static void write(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() throws IOException {
        server.stop(0);
        requests.shutdownNow();
        requests.close();
        http.close();
        audit.close();
    }

    /** Gemini 配置要求 HTTPS。复用原请求的头、正文、超时及处理器，仅把传输目的地指向本地供应商。 */
    private static final class GeminiLoopback extends HttpClient {
        private final HttpClient client;
        private final URI endpoint;
        GeminiLoopback(HttpClient client, URI endpoint) { this.client = client; this.endpoint = endpoint; }

        private HttpRequest local(HttpRequest request) {
            return HttpRequest.newBuilder(request, (name, value) -> true).uri(endpoint).version(Version.HTTP_1_1).build();
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException { return client.send(local(request), handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return client.sendAsync(local(request), handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { return client.sendAsync(local(request), handler, push); }
        @Override public Optional<CookieHandler> cookieHandler() { return client.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return client.connectTimeout(); }
        @Override public Redirect followRedirects() { return client.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return client.proxy(); }
        @Override public SSLContext sslContext() { return client.sslContext(); }
        @Override public SSLParameters sslParameters() { return client.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return client.authenticator(); }
        @Override public Version version() { return client.version(); }
        @Override public Optional<Executor> executor() { return client.executor(); }
    }
}
