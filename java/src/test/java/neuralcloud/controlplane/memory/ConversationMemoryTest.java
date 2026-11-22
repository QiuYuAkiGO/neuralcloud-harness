package neuralcloud.controlplane.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConversationMemoryTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void reopensDurableRecordsOnlyWithinChannelAndBeforeCurrentTurn() throws Exception {
        var records = new LinkedHashMap<String, JsonObject>();
        var gson = new Gson();
        var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/v1/", exchange -> {
            try (exchange) {
                String route = exchange.getRequestURI().getPath();
                if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String response;
                if ("/v1/ingest".equals(route)) {
                    var source = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    int sequence = records.size() + 1;
                    String id = "%064x".formatted(sequence);
                    records.put(id, source);
                    response = """
                            {"status":"DURABLE","record_id":"%s","revision":1,"seq":%d}
                            """.formatted(id, sequence);
                } else if ("/v1/recall".equals(route)) {
                    var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    String query = request.get("query").getAsString();
                    var hits = records.entrySet().stream()
                            .filter(entry -> entry.getValue().get("content").getAsString().contains(query))
                            .map(entry -> online(entry.getKey(), entry.getValue())).toList();
                    response = gson.toJson(Map.of("status", "OK", "items", hits));
                } else {
                    String id = route.substring(route.lastIndexOf('/') + 1);
                    response = online(id, records.get(id)).toString();
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        service.start();
        try (var audit = AuditLog.open(folder.getRoot().toPath()); var client = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + service.getAddress().getPort() + "/");
            var memory = new ConversationMemory(audit,
                    new ResidentMemory(client, endpoint, "misty-java", "test-token", false));
            var qq = start(audit, "openqq", "QQ 私聊里的内容");
            memory.rememberInbound(qq, "QQ 私聊里的内容");
            var first = start(audit, "console", "我喜欢拍照。");
            int stored = memory.rememberInbound(first, "我喜欢拍照。");
            int delivered = first.write(stored, "console", "console_response_written", Map.of());
            memory.rememberDelivered(first, delivered, "我也喜欢。");

            var second = start(audit, "console", "你记得我刚才说的吗？");
            int current = memory.rememberInbound(second, "你记得我刚才说的吗？");
            var settings = new Master.Settings(endpoint.resolve("v1/chat/completions"), "test-router",
                    HttpClient.Version.HTTP_1_1, Duration.ofSeconds(2), Duration.ofSeconds(2), "probe", 8, 0, false);
            var router = new Router<>(new Master(client, settings), "选择流程。", Route.class);
            var recalled = router.queryMemory(memory, second, current, ConversationMemory.QueryKind.HISTORY, "摄影");
            assertEquals(3, recalled.messages().size());
            assertEquals("本次只回读了当前会话的近期对话，共 2 条。", recalled.messages().getFirst());
            assertTrue(recalled.messages().get(1).startsWith("用户在北京时间 "));
            assertTrue(recalled.messages().get(1).endsWith("说：“我喜欢拍照。”。来源是当前控制台会话中已记录的用户发言。"));
            assertTrue(recalled.messages().getLast().endsWith("说：“我也喜欢。”。来源是当前控制台会话中已记录的小雾回复。"));
            var events = audit.events("shared");
            var returned = events.getLast();
            assertEquals(recalled.eventId(), returned.eventId());
            assertEquals("router_memory_result", returned.type());
            assertEquals(0, returned.data().getAsJsonObject().get("count").getAsInt());
            assertEquals(gson.toJsonTree(recalled.messages()), returned.data().getAsJsonObject().get("sentences"));
            assertTrue(events.stream().anyMatch(event -> Integer.valueOf(delivered).equals(event.causedBy())
                    && "SELF_EXPRESSION".equals(event.data().getAsJsonObject().get("kind").getAsString())));

            var empty = router.queryMemory(memory, second, current, ConversationMemory.QueryKind.SELF_IDENTITY, "");
            assertEquals(List.of("未查到符合条件的记录。"), empty.messages());
            var unmatched = audit.events("shared").getLast().data().getAsJsonObject();
            assertEquals(0, unmatched.get("count").getAsInt());
            assertEquals(gson.toJsonTree(empty.messages()), unmatched.get("sentences"));

            // 同一会话已有持久回执，按话题检索仍须分别交付零命中和单条命中的真实结果。
            var scoped = new ConversationMemory(audit,
                    new ResidentMemory(client, endpoint, "misty-java", "test-token", true));
            var miss = router.queryMemory(scoped, second, current, ConversationMemory.QueryKind.HISTORY, "露营");
            assertEquals(List.of("当前会话中未查到符合条件的历史记录。"), miss.messages());
            var report = audit.events("shared").getLast().data().getAsJsonObject();
            assertEquals(0, report.get("count").getAsInt());
            assertEquals(gson.toJsonTree(miss.messages()), report.get("sentences"));

            var hit = router.queryMemory(scoped, second, current, ConversationMemory.QueryKind.HISTORY, "拍照");
            assertEquals(List.of(recalled.messages().get(1)), hit.messages());
            report = audit.events("shared").getLast().data().getAsJsonObject();
            assertEquals(1, report.get("count").getAsInt());
            assertEquals(gson.toJsonTree(hit.messages()), report.get("sentences"));
        } finally {
            service.stop(0);
        }
    }

    private static JsonObject online(String id, JsonObject event) {
        var source = event.deepCopy();
        var item = new JsonObject();
        item.addProperty("status", "ONLINE");
        item.addProperty("record_id", id);
        item.addProperty("revision", 1);
        item.add("kind", source.get("kind"));
        item.add("occurred_at", source.get("occurred_at"));
        item.add("content", source.remove("content"));
        item.add("source", source);
        return item;
    }

    @Test
    public void productionRecallAdmitsOnlyEarlierAuthorizedPrivateHistory() throws Exception {
        var received = new AtomicReference<JsonObject>();
        var ingested = new AtomicReference<JsonObject>();
        var qq = new Trace.QqPrivate(10001, 10002);
        var scope = new Trace.Scope("openqq", "test", "run", Optional.of(qq));
        String conversation = qq.conversationId();
        var gson = new Gson();
        var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/v1/recall", exchange -> {
            try (exchange) {
                received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                boolean identity = received.get().has("intent");
                var items = identity ? List.of(
                        identity("verified", "10002", "operator_confirmed", "private", false, "测试称呼"),
                        identity("other", "10003", "operator_confirmed", "private", false, "他人称呼"),
                        identity("unverified", "10002", "self_reported", "private", false, "自报称呼"),
                        identity("conflict", "10002", "operator_confirmed", "private", true, "冲突称呼"),
                        identity("sensitive", "10002", "operator_confirmed", "sensitive", false, "敏感称呼"))
                        : List.of(
                        indexed("old", conversation, "onebot:10001", "private", 10, Map.of()),
                        indexed("foreign", "group:onebot:10001:10009", "onebot:10001", "private", 11, Map.of()),
                        indexed("sensitive", conversation, "onebot:10001", "sensitive", 12, Map.of()),
                        indexed("same-turn", conversation, "onebot:10001", "private", 20, Map.of()),
                        indexed("wrong-sender", conversation, "neuralcloud-java", "private", 13, Map.of("channel",
                                channel(conversation, "10003"))),
                        indexed("new", conversation, "neuralcloud-java", "private", 14, Map.of("channel",
                                channel(conversation, "10002"))));
                byte[] bytes = gson.toJson(Map.of("status", "OK", "items", items)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        service.createContext("/v1/ingest", exchange -> {
            try (exchange) {
                ingested.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] bytes = gson.toJson(Map.of("status", "DURABLE", "record_id", "a".repeat(64),
                        "revision", 1, "seq", 1)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        service.start();
        try (var client = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + service.getAddress().getPort() + "/");
            var memory = new ResidentMemory(client, endpoint, "misty", "test-token", true);
            var now = Instant.ofEpochSecond(20);
            var results = memory.search(scope, "之前说过什么？", now);
            assertEquals(List.of("old", "new"), results.stream().map(ResidentMemory.Match::recordId).toList());
            var constraints = received.get().getAsJsonObject("constraints");
            assertEquals(conversation, constraints.get("conversation_id").getAsString());
            assertTrue(constraints.get("until").getAsDouble() < 20);
            memory.ingest(scope, "run:1", now, ResidentMemory.Kind.OBSERVATION, "这是一段私聊。", "47");
            assertEquals("openqq:test", ingested.get().get("session_id").getAsString());
            assertEquals(2, ingested.get().get("schema_version").getAsInt());
            assertEquals(conversation, ingested.get().get("conversation_id").getAsString());
            assertEquals("10002", ingested.get().getAsJsonObject("channel").get("sender_id").getAsString());
            assertEquals("47", ingested.get().getAsJsonObject("channel").get("message_id").getAsString());

            var mine = memory.selfIdentity(scope).orElseThrow();
            assertEquals("verified", mine.recordId());
            assertEquals("当前 QQ 账号经确认的称呼是「测试称呼」。来源是记忆服务中经操作者确认的本人身份资料。",
                    mine.message());
            assertEquals("10002", received.get().getAsJsonObject("subject").get("account_id").getAsString());
            assertTrue(!received.get().has("constraints"));
            assertTrue(memory.selfIdentity(new Trace.Scope("console", "test", "run")).isEmpty());
        } finally {
            service.stop(0);
        }
    }

    private static Map<String, String> channel(String conversation, String sender) {
        return Map.of("platform", "qq", "account_id", "10001", "sender_id", sender,
                "conversation_id", conversation);
    }

    private static Map<String, Object> identity(String id, String account, String confirmation, String privacy,
                                                boolean conflict, String name) {
        return Map.of("status", "ONLINE", "record_id", id, "source", Map.of("character_id", "misty", "privacy", privacy),
                "content", "来源原文不对外返回", "identity", Map.of("platform", "qq", "account_id", account,
                        "confirmation", confirmation, "conflict", conflict, "name", name));
    }

    private static Map<String, Object> indexed(String id, String conversation, String writer, String privacy,
                                                int occurred, Map<String, Object> extra) {
        var source = new LinkedHashMap<>(Map.of("character_id", "misty", "kind", "observation",
                "conversation_id", conversation, "source_instance", writer, "privacy", privacy));
        Map<String, Object> metadata = new LinkedHashMap<>(source);
        metadata.putAll(extra);
        return Map.of("status", "ONLINE", "record_id", id, "revision", 1, "kind", "observation",
                "occurred_at", occurred, "content", "原始发言 " + id, "source", metadata);
    }

    private static Trace start(AuditLog audit, String channel, String text) throws Exception {
        int request = audit.write("shared", null, channel, "input", Map.of("text", text));
        return Trace.forChain(audit, channel, "shared", request);
    }
}
