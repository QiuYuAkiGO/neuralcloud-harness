package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;

final class MasterConfig {
    private static final Pattern PROXY = Pattern.compile("([A-Za-z0-9.-]+):([0-9]{1,5})");

    private MasterConfig() {
    }

    // 启动入口在此校验配置，Master 只接收已校验的类型和值。
    static Master.Settings load(ConfigSource source) throws IOException {
        return load(source, System::getenv);
    }

    static Master.Settings load(ConfigSource source, Function<String, String> env) throws IOException {
        Properties properties;
        try {
            properties = source.readProperties();
        } catch (IOException error) {
            throw new IOException("无法读取配置文件 " + source.location() + "（" + error.getMessage() + "）", error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("配置文件格式无效 " + source.location(), error);
        }

        URI endpoint = endpoint(required(properties, "endpoint"));
        boolean chat = isChatEndpoint(endpoint);
        Boolean enableThinking = chat ? booleanField(properties, "enableThinking") : null;
        // 本机推理端点默认沿用 chat_template_kwargs，云端可显式选择 DEEPSEEK 或 NONE。
        Master.ThinkingFormat thinkingFormat = chat
                ? optionalThinkingFormat(properties, Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS) : null;
        String apiKey = optionalApiKey(properties, env, "推理服务");
        InetSocketAddress proxy = optionalProxy(properties, "推理服务");
        Master.Settings settings = new Master.Settings(
                endpoint,
                required(properties, "model"),
                httpVersion(required(properties, "httpVersion")),
                Duration.ofSeconds(positiveInteger(properties, "connectTimeoutSeconds")),
                Duration.ofSeconds(positiveInteger(properties, "requestTimeoutSeconds")),
                required(properties, "probePrompt"),
                positiveInteger(properties, "probeMaxTokens"),
                temperature(required(properties, "probeTemperature")),
                enableThinking,
                apiKey,
                thinkingFormat,
                proxy);
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("未知配置项 " + properties.stringPropertyNames());
        }
        return settings;
    }

    static String required(Properties properties, String key) {
        String value = (String) properties.remove(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少或留空配置项 " + key);
        }
        return value;
    }

    static String optional(Properties properties, String key) {
        if (!properties.containsKey(key)) {
            return null;
        }
        String value = (String) properties.remove(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("配置项 " + key + " 不能为空");
        }
        return value;
    }

    static boolean isChatEndpoint(URI endpoint) {
        String path = endpoint.getPath();
        return path != null && (path.endsWith("/v1/chat/completions") || path.endsWith("/chat/completions"));
    }

    private static URI endpoint(String value) {
        URI endpoint = httpEndpoint(value, "endpoint 须指向 /v1/completions、/v1/chat/completions 或 /chat/completions，且无凭据、查询或片段");
        String path = endpoint.getPath();
        if (!(path.endsWith("/v1/completions") || isChatEndpoint(endpoint))) {
            throw new IllegalArgumentException("endpoint 须指向 /v1/completions、/v1/chat/completions 或 /chat/completions，且无凭据、查询或片段");
        }
        return endpoint;
    }

    static URI chatEndpoint(String value, String label) {
        String message = label + " endpoint 须为不含凭据、查询或片段的 HTTP(S) /v1/chat/completions 或 /chat/completions 地址";
        URI endpoint = httpEndpoint(value, message);
        if (!isChatEndpoint(endpoint)) {
            throw new IllegalArgumentException(message);
        }
        return endpoint;
    }

    private static URI httpEndpoint(String value, String message) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint 不是有效的 URI", error);
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getPath() == null) {
            throw new IllegalArgumentException(message);
        }
        return endpoint;
    }

    static boolean booleanField(Properties properties, String key) {
        return switch (required(properties, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " 须为 true 或 false");
        };
    }

    static Master.ThinkingFormat optionalThinkingFormat(Properties properties, Master.ThinkingFormat fallback) {
        String value = optional(properties, "thinkingFormat");
        if (value == null) {
            return fallback;
        }
        try {
            return Master.ThinkingFormat.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "thinkingFormat 须为 " + Arrays.toString(Master.ThinkingFormat.values()), error);
        }
    }

    static String optionalApiKey(Properties properties, Function<String, String> env, String label) {
        String keyEnv = optional(properties, "apiKeyEnv");
        if (keyEnv == null) {
            return null;
        }
        String key = env.apply(keyEnv);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缺少" + label + "密钥环境变量 " + keyEnv);
        }
        return key;
    }

    static InetSocketAddress optionalProxy(Properties properties, String label) {
        String value = optional(properties, "proxy");
        if (value == null) {
            return null;
        }
        var matcher = PROXY.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(label + " proxy 须为 主机:端口，端口在 1 至 65535 之间");
        }
        int port = Integer.parseInt(matcher.group(2));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + " proxy 须为 主机:端口，端口在 1 至 65535 之间");
        }
        return new InetSocketAddress(matcher.group(1), port);
    }

    static HttpClient.Version httpVersion(String value) {
        try {
            return HttpClient.Version.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("httpVersion 须为 HTTP_1_1 或 HTTP_2");
        }
    }

    static int positiveInteger(Properties properties, String key) {
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
