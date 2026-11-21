package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

final class MasterConfig {
    private MasterConfig() {
    }

    // 启动入口在此校验外部配置，Master 只接收已校验的类型和值。
    static Master.Settings load(Path path) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new IOException("无法读取配置文件 " + path + "（" + error.getMessage() + "）", error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("配置文件格式无效 " + path, error);
        }

        URI endpoint = endpoint(required(properties, "endpoint"));
        Boolean enableThinking = endpoint.getPath().endsWith("/v1/chat/completions") ? thinking(properties) : null;
        Master.Settings settings = new Master.Settings(
                endpoint,
                required(properties, "model"),
                httpVersion(required(properties, "httpVersion")),
                Duration.ofSeconds(positiveInteger(properties, "connectTimeoutSeconds")),
                Duration.ofSeconds(positiveInteger(properties, "requestTimeoutSeconds")),
                required(properties, "probePrompt"),
                positiveInteger(properties, "probeMaxTokens"),
                temperature(required(properties, "probeTemperature")), enableThinking);
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("未知配置项 " + properties.stringPropertyNames());
        }
        return settings;
    }

    private static String required(Properties properties, String key) {
        String value = (String) properties.remove(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少或留空配置项 " + key);
        }
        return value;
    }

    private static URI endpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint 不是有效的 URI");
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !(endpoint.getPath().endsWith("/v1/completions") || endpoint.getPath().endsWith("/v1/chat/completions"))) {
            throw new IllegalArgumentException("endpoint 须指向 /v1/completions 或 /v1/chat/completions，且无凭据、查询或片段");
        }
        return endpoint;
    }

    private static boolean thinking(Properties properties) {
        return switch (required(properties, "enableThinking")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("enableThinking 须为 true 或 false");
        };
    }

    private static HttpClient.Version httpVersion(String value) {
        try {
            return HttpClient.Version.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("httpVersion 须为 HTTP_1_1 或 HTTP_2");
        }
    }

    private static int positiveInteger(Properties properties, String key) {
        String value = required(properties, key);
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " 须为正整数", error);
        }
        if (number <= 0) {
            throw new IllegalArgumentException(key + " 须为正整数");
        }
        return number;
    }

    private static double temperature(String value) {
        double number;
        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("probeTemperature 须为非负有限数值", error);
        }
        if (!Double.isFinite(number) || number < 0) {
            throw new IllegalArgumentException("probeTemperature 须为非负有限数值");
        }
        return number;
    }
}
