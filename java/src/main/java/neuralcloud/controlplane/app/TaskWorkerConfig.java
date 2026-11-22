package neuralcloud.controlplane.app;

import neuralcloud.controlplane.worker.ServerStatus;
import neuralcloud.controlplane.worker.TaskWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Set;

record TaskWorkerConfig(URI endpoint, String model, String apiKey, Duration connectTimeout, Duration requestTimeout) {
    private static final Set<String> FIELDS = Set.of("endpoint", "model", "apiKeyEnv", "connectTimeout", "requestTimeout");

    static TaskWorkerConfig load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        if (!properties.stringPropertyNames().equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("任务 Worker 配置须包含以下非空字段 " + FIELDS);
        }
        var endpoint = URI.create(properties.getProperty("endpoint"));
        if (!endpoint.equals(URI.create("https://api.deepseek.com/chat/completions"))) {
            throw new IllegalArgumentException("任务 Worker endpoint 须为 DeepSeek 官方 HTTPS 聊天接口");
        }
        String keyEnvironment = properties.getProperty("apiKeyEnv");
        String key = System.getenv(keyEnvironment);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缺少任务 Worker 的 DeepSeek 密钥环境变量 " + keyEnvironment);
        }
        if (!"Linux".equals(System.getProperty("os.name"))) {
            throw new IllegalArgumentException("任务 Worker 的服务器状态读取能力需要 Linux");
        }
        try {
            var connect = Duration.parse(properties.getProperty("connectTimeout"));
            var request = Duration.parse(properties.getProperty("requestTimeout"));
            if (connect.isPositive() && request.isPositive()) {
                return new TaskWorkerConfig(endpoint, properties.getProperty("model"), key, connect, request);
            }
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("connectTimeout 和 requestTimeout 须为正的 ISO 8601 时长", error);
        }
        throw new IllegalArgumentException("connectTimeout 和 requestTimeout 须为正的 ISO 8601 时长");
    }

    TaskWorker create(HttpClient client) {
        return new TaskWorker(client, endpoint, model, apiKey, requestTimeout, ServerStatus::readLinux);
    }
}
