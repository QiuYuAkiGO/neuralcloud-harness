package neuralcloud.controlplane.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            var memory = new ConversationMemory(audit, new ResidentMemory(client, endpoint, "misty-java", "test-token"));
            var qq = start(audit, "openqq", "QQ 私聊里的内容");
            memory.rememberInbound(qq, "QQ 私聊里的内容");
            var first = start(audit, "console", "我喜欢拍照。");
            int stored = memory.rememberInbound(first, "我喜欢拍照。");
            int delivered = first.write(stored, "console", "console_response_written", Map.of());
            memory.rememberDelivered(first, delivered, "我也喜欢。");

            var second = start(audit, "console", "你记得我刚才说的吗？");
            int current = memory.rememberInbound(second, "你记得我刚才说的吗？");
            var recalled = memory.recent(second, current);
            assertEquals(List.of("用户说：我喜欢拍照。", "小雾说：我也喜欢。"), recalled.messages());
            var events = audit.events("shared");
            assertEquals(Integer.valueOf(current), events.getLast().causedBy());
            assertTrue(events.stream().anyMatch(event -> Integer.valueOf(delivered).equals(event.causedBy())
                    && "SELF_EXPRESSION".equals(event.data().getAsJsonObject().get("kind").getAsString())));
        } finally {
            service.stop(0);
        }
    }

    private static Trace start(AuditLog audit, String channel, String text) throws Exception {
        int request = audit.write("shared", null, channel, "input", Map.of("text", text));
        return Trace.forChain(audit, channel, "shared", request);
    }
}
