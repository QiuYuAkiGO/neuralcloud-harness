package neuralcloud.controlplane.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 同源页签只向启动参数指定的回环端口转发实验请求；不转交控制台身份、Cookie 或渠道凭据。 */
final class PlaygroundProxy {
    private PlaygroundProxy() {}
    static void mount(HttpServer server) {
        String value = System.getProperty("neuralcloud.playground.port");
        if (value == null) {
            server.createContext("/api/playground/", exchange -> {
                try (exchange) {
                    send(exchange, 503, "{\"error\":\"该控制台尚未配置隔离实验服务\"}".getBytes(StandardCharsets.UTF_8));
                }
            });
            return;
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("neuralcloud.playground.port 超出范围");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .proxy(HttpClient.Builder.NO_PROXY).build();
        server.createContext("/api/playground/", exchange -> forward(exchange, client, port));
    }

    private static void forward(HttpExchange exchange, HttpClient client, int port) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("POST")) {
                send(exchange, 405, "{\"error\":\"实验接口仅接受 GET 或 POST\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            var target = URI.create("http://127.0.0.1:" + port + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(90));
            if (method.equals("POST")) {
                int limit = target.getPath().endsWith("/scores") ? 4 * 1024 * 1024 : 262144;
                byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (body.length > limit || type == null || !type.split(";")[0].equals("application/json")) {
                    send(exchange, 400, "{\"error\":\"实验 JSON 请求大小或媒体类型无效\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            }
            try {
                var result = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                send(exchange, result.statusCode(), result.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } catch (IOException unavailable) {
                System.err.println("实验服务连接失败 " + unavailable);
                send(exchange, 503, "{\"error\":\"隔离实验服务暂不可用\"}".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
