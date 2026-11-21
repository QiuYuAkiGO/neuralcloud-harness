package neuralcloud.controlplane.app;

import neuralcloud.controlplane.worker.ChatWorker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

final class ChatWorkerConfig {
    private static final Set<String> FIELDS = Set.of("endpoint", "apiKeyEnv", "httpVersion", "proxy",
            "connectTimeoutSeconds", "requestTimeoutSeconds", "maxOutputTokens", "thinkingLevel");
    private static final Pattern PROXY = Pattern.compile("([A-Za-z0-9.-]+):([0-9]{1,5})");

    private ChatWorkerConfig() {}

    static Settings load(Path path) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        if (!properties.stringPropertyNames().equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("Chat Worker 配置须包含以下非空字段 " + FIELDS);
        }
        URI endpoint = URI.create(properties.getProperty("endpoint"));
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || !endpoint.getPath().matches("/v1beta/models/[A-Za-z0-9._-]+:generateContent")
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Chat Worker endpoint 须为不含凭据的 HTTPS Gemini generateContent 地址");
        }
        String level = properties.getProperty("thinkingLevel");
        if (!List.of("MINIMAL", "LOW", "MEDIUM", "HIGH").contains(level)) {
            throw new IllegalArgumentException("Chat Worker thinkingLevel 须为 MINIMAL、LOW、MEDIUM 或 HIGH");
        }
        var proxy = PROXY.matcher(properties.getProperty("proxy"));
        if (!proxy.matches() || Integer.parseInt(proxy.group(2)) < 1 || Integer.parseInt(proxy.group(2)) > 65535) {
            throw new IllegalArgumentException("Chat Worker proxy 须为 主机:端口，端口在 1 至 65535 之间");
        }
        return new Settings(endpoint, properties.getProperty("apiKeyEnv"),
                HttpClient.Version.valueOf(properties.getProperty("httpVersion")),
                new InetSocketAddress(proxy.group(1), Integer.parseInt(proxy.group(2))),
                Duration.ofSeconds(positive(properties, "connectTimeoutSeconds")),
                Duration.ofSeconds(positive(properties, "requestTimeoutSeconds")),
                positive(properties, "maxOutputTokens"), level);
    }

    record Settings(URI endpoint, String keyEnvironment, HttpClient.Version httpVersion, InetSocketAddress proxy,
                    Duration connectTimeout, Duration requestTimeout, int maxOutputTokens, String thinkingLevel) {
        // Gemini 请求经配置的 HTTP 代理以 CONNECT 隧道发出，TLS 仍在客户端与 Gemini 之间建立。
        HttpClient newClient() {
            return HttpClient.newBuilder().version(httpVersion).proxy(ProxySelector.of(proxy))
                    .connectTimeout(connectTimeout).build();
        }

        String apiKey() {
            String key = System.getenv(keyEnvironment);
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("缺少 Gemini 密钥环境变量 " + keyEnvironment);
            }
            return key;
        }

        ChatWorker create(HttpClient client, String apiKey, String rolePrompt) {
            return new ChatWorker(client, endpoint, requestTimeout, maxOutputTokens, thinkingLevel, apiKey, rolePrompt);
        }
    }

    private static int positive(Properties properties, String field) {
        try {
            int value = Integer.parseInt(properties.getProperty(field));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " 须为正整数", error);
        }
        throw new IllegalArgumentException(field + " 须为正整数");
    }
}
