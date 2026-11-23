package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.master.MasterRuntime;
import neuralcloud.controlplane.orchestration.MasterEpochs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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

import static org.junit.Assert.*;

/** 走控制台正式 Master 处理器，用本地推理挡板检查跨代实际模型输入及命令边界。 */
public class MasterEpochConsoleTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void projectsOnlyTheAudiencesOpenMatterIntoTheNextModelRequestAfterRestart() throws Exception {
        var prompts = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")
                    .get(0).getAsJsonObject().get("content").getAsString());
            var response = new Gson().toJson(Map.of("model", "test-master", "choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", "本轮检查完成")))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var dir = folder.getRoot().toPath();
        var path = dir.resolve("master-epochs.sqlite");
        var profile = new MasterEpochs.Profile(24, 100);
        var timeout = Duration.ofSeconds(3);
        String promise = "周五前追踪供应商的书面回执";
        String old = "已经核销的旧事项".repeat(5);
        try (var http = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            var model = new Master(http, new Master.Settings(endpoint, "test-master", HttpClient.Version.HTTP_1_1,
                    timeout, timeout, "探测", 8, 0, false));
            try (var audit = AuditLog.open(dir); var epochs = new MasterEpochs(path, profile)) {
                var exchange = ConsoleMain.masterConversation(new MasterRuntime(model, epochs, 64, 0), Optional.empty());
                assertEquals("已登记未决事项 1", send(exchange, audit, "A", "/epoch open " + promise).data().get("text"));
                send(exchange, audit, "A", old);
            }
            try (var audit = AuditLog.open(dir); var epochs = new MasterEpochs(path, profile)) {
                var exchange = ConsoleMain.masterConversation(new MasterRuntime(model, epochs, 64, 0), Optional.empty());
                send(exchange, audit, "B", "检查本会话的事项");
                assertFalse(prompts.getLast().contains(promise));
                assertThrows(IOException.class, () -> send(exchange, audit, "B", "/epoch resolve 1 越界"));
                send(exchange, audit, "A", "继续追踪");
                assertTrue(prompts.getLast().contains("事项 1 仍待处理：" + promise));
                assertFalse(prompts.getLast().contains(old));
                assertTrue(prompts.getLast().endsWith("本轮请求：\n继续追踪"));
                send(exchange, audit, "A", "/epoch resolve 1 供应商已提供书面回执");
                send(exchange, audit, "A", "检查进度");
                assertFalse(prompts.getLast().contains("事项 1 仍待处理"));
                assertEquals(4, prompts.size());
            }
        } finally { server.stop(0); }
    }

    /** 每次入站由渠道分配审计标识，与普通网页请求相同。 */
    private static ConsoleServer.Reply send(ConsoleServer.Exchange exchange, AuditLog audit, String chain, String text)
            throws IOException, InterruptedException {
        int request = audit.write(chain, null, "master", "operator_message", Map.of("text", text));
        return exchange.send(text, ConsoleServer.Trace.forChain(audit, "console", chain, request));
    }
}
