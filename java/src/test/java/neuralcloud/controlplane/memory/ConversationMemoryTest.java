package neuralcloud.controlplane.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
                } else {
                    String id = route.substring(route.lastIndexOf('/') + 1);
                    var source = records.get(id).deepCopy();
                    var item = new JsonObject();
                    item.addProperty("status", "ONLINE");
                    item.addProperty("record_id", id);
                    item.addProperty("revision", 1);
                    item.add("kind", source.get("kind"));
                    item.add("content", source.remove("content"));
                    item.add("source", source);
                    response = item.toString();
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
            var recalled = memory.recall(second, current, "你记得我刚才说的吗？");
            assertEquals(List.of("用户说：我喜欢拍照。", "小雾说：我也喜欢。"), recalled.messages());
            var events = audit.events("shared");
            assertEquals(Integer.valueOf(current), events.getLast().causedBy());
            assertTrue(events.stream().anyMatch(event -> Integer.valueOf(delivered).equals(event.causedBy())
                    && "SELF_EXPRESSION".equals(event.data().getAsJsonObject().get("kind").getAsString())));
        } finally {
            service.stop(0);
        }
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
                byte[] bytes = gson.toJson(Map.of("status", "OK", "items", List.of(
                        indexed("old", conversation, "onebot:10001", "private", 10, Map.of()),
                        indexed("foreign", "group:onebot:10001:10009", "onebot:10001", "private", 11, Map.of()),
                        indexed("sensitive", conversation, "onebot:10001", "sensitive", 12, Map.of()),
                        indexed("same-turn", conversation, "onebot:10001", "private", 20, Map.of()),
                        indexed("wrong-sender", conversation, "neuralcloud-java", "private", 13, Map.of("channel",
                                channel(conversation, "10003"))),
                        indexed("new", conversation, "neuralcloud-java", "private", 14, Map.of("channel",
                                channel(conversation, "10002")))))).getBytes(StandardCharsets.UTF_8);
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
        } finally {
            service.stop(0);
        }
    }

    private static Map<String, String> channel(String conversation, String sender) {
        return Map.of("platform", "qq", "account_id", "10001", "sender_id", sender,
                "conversation_id", conversation);
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
