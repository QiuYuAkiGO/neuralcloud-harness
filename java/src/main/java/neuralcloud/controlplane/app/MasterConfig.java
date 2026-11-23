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

/**
 * Master 与 Router 共用的推理服务配置加载器。
 * 职责：在启动边界严格校验端点路径、协议版本、超时、探测参数、鉴权密钥与可选代理，
 * 并通过“读取即从 Properties 移除，最后检查是否残留未知键”的方式拦截拼写错误或未声明配置项。
 * 同时提供若干包级辅助方法，供 ChatWorkerConfig 与 TaskWorkerConfig 复用同一套解析契约。
 */
final class MasterConfig {
    /** 代理地址格式约束：主机名（或 IPv4）加冒号与 1~5 位端口号。 */
    private static final Pattern PROXY = Pattern.compile("([A-Za-z0-9.-]+):([0-9]{1,5})");

    private MasterConfig() {
    }

    /** 使用当前进程环境变量加载并校验 Master 配置；Master 内部只接收已校验的类型和值。 */
    static Master.Settings load(ConfigSource source) throws IOException {
        return load(source, System::getenv);
    }

    /**
     * 加载并校验推理服务配置（允许注入环境变量读取函数，便于测试隔离）。
     * 续写端点（/v1/completions）禁止配置聊天专用的 enableThinking/thinkingFormat，
     * 因为未被消费的键会在末尾的未知配置项检查中被统一拒绝。
     */
    static Master.Settings load(ConfigSource source, Function<String, String> env) throws IOException {
        Properties properties;
        try {
            properties = source.readProperties();
        } catch (IOException error) {
            throw new IOException("无法读取配置文件 " + source.location() + "（" + error.getMessage() + "）", error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("配置文件格式无效 " + source.location(), error);
        }

        // 先根据端点路径区分聊天接口与原始续写接口，再按接口类型消费对应配置项。
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
        // 所有合法配置项均已通过 remove 消费，任何残留键均视为未知或不适用于当前端点类型的字段。
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("未知配置项 " + properties.stringPropertyNames());
        }
        return settings;
    }

    /** 读取并移除必填非空字符串配置项；缺失或全空白直接报错。 */
    static String required(Properties properties, String key) {
        String value = (String) properties.remove(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少或留空配置项 " + key);
        }
        return value;
    }

    /** 读取并移除可选字符串配置项；未声明时返回 null，但显式写出却留空仍视为配置错误。 */
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

    /** 判断端点是否为 OpenAI 兼容的聊天补全接口（兼容带或不带 /v1 前缀的云端与本机路径）。 */
    static boolean isChatEndpoint(URI endpoint) {
        String path = endpoint.getPath();
        return path != null && (path.endsWith("/v1/chat/completions") || path.endsWith("/chat/completions"));
    }

    /** 校验 Master 支持的端点：允许原始续写接口或聊天补全接口，且禁止携带凭据、查询参数与片段。 */
    private static URI endpoint(String value) {
        URI endpoint = httpEndpoint(value, "endpoint 须指向 /v1/completions、/v1/chat/completions 或 /chat/completions，且无凭据、查询或片段");
        String path = endpoint.getPath();
        if (!(path.endsWith("/v1/completions") || isChatEndpoint(endpoint))) {
            throw new IllegalArgumentException("endpoint 须指向 /v1/completions、/v1/chat/completions 或 /chat/completions，且无凭据、查询或片段");
        }
        return endpoint;
    }

    /** 校验仅支持聊天补全的节点端点（如 Chat Worker 的 OpenAI 模式与 Task Worker）。 */
    static URI chatEndpoint(String value, String label) {
        String message = label + " endpoint 须为不含凭据、查询或片段的 HTTP(S) /v1/chat/completions 或 /chat/completions 地址";
        URI endpoint = httpEndpoint(value, message);
        if (!isChatEndpoint(endpoint)) {
            throw new IllegalArgumentException(message);
        }
        return endpoint;
    }

    /** 公共 HTTP(S) URI 合法性检查：限制协议、主机与端口范围，禁止在 URL 中夹带用户信息或查询串。 */
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

    /** 严格解析布尔配置项，只接受字面量 "true" 或 "false"，避免 Boolean.parseBoolean 静默吞掉非法值。 */
    static boolean booleanField(Properties properties, String key) {
        return switch (required(properties, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " 须为 true 或 false");
        };
    }

    /** 解析可选的思考开关格式枚举，未配置时使用调用方传入的默认格式。 */
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

    /**
     * 按配置项 {@code apiKeyEnv} 指定的环境变量名读取 Bearer 密钥。
     * 未配置时返回 null（适用于免鉴权本机推理服务）；已配置但环境变量缺失或为空则立即拒绝启动。
     */
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

    /** 解析可选的 HTTP 代理配置（主机:端口），未配置时返回 null 表示直连端点。 */
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

    /** 解析 Java HttpClient 协议版本枚举（HTTP_1_1 或 HTTP_2）。 */
    static HttpClient.Version httpVersion(String value) {
        try {
            return HttpClient.Version.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("httpVersion 须为 HTTP_1_1 或 HTTP_2");
        }
    }

    /** 解析必填正整数配置项（用于超时秒数、最大 token 数等边界校验）。 */
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

    /** 解析采样温度，仅允许非负有限浮点数（拒绝 NaN、Infinity 与负数）。 */
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
