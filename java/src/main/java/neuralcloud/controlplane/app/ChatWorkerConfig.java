package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Chat Worker（前台对话角色模型）的配置加载与实例装配器。
 * 支持两种后端协议：
 * 1. {@link ApiFormat#GEMINI}（默认）：Google Gemini {@code generateContent} HTTPS 接口，使用 {@code thinkingLevel} 控制思考深度；
 * 2. {@link ApiFormat#OPENAI_CHAT}：兼容本机或云端 OpenAI 聊天接口，使用 {@code model}、{@code enableThinking} 与 {@code thinkingFormat}。
 * 启动阶段校验所有必填/互斥字段并加载只读回复偏好摘要 {@code replyPreferences}。
 */
final class ChatWorkerConfig {
    private ChatWorkerConfig() {}

    /**
     * 读取并校验 Chat Worker 配置文件。
     * 先解析两种协议共用的网络、超时、输出上限和回复偏好摘要，再按 {@code apiFormat} 分支校验协议专有字段，
     * 并在末尾检查是否存在未消费的未知配置项。
     */
    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        ApiFormat format = apiFormat(properties);
        HttpClient.Version httpVersion = MasterConfig.httpVersion(MasterConfig.required(properties, "httpVersion"));
        InetSocketAddress proxy = MasterConfig.optionalProxy(properties, "Chat Worker");
        Duration connectTimeout = Duration.ofSeconds(MasterConfig.positiveInteger(properties, "connectTimeoutSeconds"));
        Duration requestTimeout = Duration.ofSeconds(MasterConfig.positiveInteger(properties, "requestTimeoutSeconds"));
        int maxOutputTokens = MasterConfig.positiveInteger(properties, "maxOutputTokens");
        int historyBudget = historyBudget(properties);
        String preferences = new ConfigSource(MasterConfig.required(properties, "replyPreferences")).readText();
        if (preferences.isBlank()) {
            throw new IllegalArgumentException("replyPreferences 须指向非空的只读偏好摘要");
        }

        // 按接口协议分流校验专有字段：Gemini 要求必填密钥与思考档位；OpenAI 聊天模式要求 model 与思考开关。
        Settings settings;
        if (format == ApiFormat.GEMINI) {
            URI endpoint = geminiEndpoint(MasterConfig.required(properties, "endpoint"));
            String keyEnvironment = MasterConfig.required(properties, "apiKeyEnv");
            String level = MasterConfig.required(properties, "thinkingLevel");
            if (!List.of("MINIMAL", "LOW", "MEDIUM", "HIGH").contains(level)) {
                throw new IllegalArgumentException("Chat Worker thinkingLevel 须为 MINIMAL、LOW、MEDIUM 或 HIGH");
            }
            settings = new Settings(format, endpoint, null, keyEnvironment, httpVersion, proxy,
                    connectTimeout, requestTimeout, maxOutputTokens, level, false, null, preferences, historyBudget);
        } else {
            URI endpoint = MasterConfig.chatEndpoint(MasterConfig.required(properties, "endpoint"), "Chat Worker");
            String model = MasterConfig.required(properties, "model");
            String keyEnvironment = MasterConfig.optional(properties, "apiKeyEnv");
            boolean enableThinking = MasterConfig.booleanField(properties, "enableThinking");
            // OpenAI 兼容聊天端点默认沿用本机推理服务的 chat_template_kwargs，云端可显式选择 DEEPSEEK 或 NONE。
            Master.ThinkingFormat thinkingFormat = MasterConfig.optionalThinkingFormat(
                    properties, Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS);
            settings = new Settings(format, endpoint, model, keyEnvironment, httpVersion, proxy,
                    connectTimeout, requestTimeout, maxOutputTokens, null, enableThinking, thinkingFormat, preferences, historyBudget);
        }
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("Chat Worker 存在未知配置项 " + properties.stringPropertyNames());
        }
        return settings;
    }

    /** 预算单位为 UTF-16。零仅在内部表示 OFF，显式预算只能用于 ROLLING。 */
    private static int historyBudget(Properties properties) {
        String mode = MasterConfig.optional(properties, "contextCompression");
        if (mode == null || mode.equals("OFF")) {
            if (properties.containsKey("historyBudgetUnits"))
                throw new IllegalArgumentException("historyBudgetUnits 仅适用于 contextCompression=ROLLING");
            return 0;
        }
        if (!mode.equals("ROLLING")) throw new IllegalArgumentException("contextCompression 须为 OFF 或 ROLLING");
        int budget = properties.containsKey("historyBudgetUnits")
                ? MasterConfig.positiveInteger(properties, "historyBudgetUnits") : 48_000;
        if (budget < 4_096 || budget > 48_000)
            throw new IllegalArgumentException("historyBudgetUnits 须在 4096 至 48000 之间");
        return budget;
    }

    /** 解析协议类型，未配置 {@code apiFormat} 时默认兼容原有 Gemini 配置。 */
    private static ApiFormat apiFormat(Properties properties) {
        String value = MasterConfig.optional(properties, "apiFormat");
        if (value == null) {
            return ApiFormat.GEMINI;
        }
        try {
            return ApiFormat.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Chat Worker apiFormat 须为 " + Arrays.toString(ApiFormat.values()), error);
        }
    }

    /** 校验 Gemini 端点格式：必须为 HTTPS 且路径精确匹配 {@code /v1beta/models/<model>:generateContent}。 */
    private static URI geminiEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Chat Worker endpoint 不是有效的 URI", error);
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getPath() == null
                || !endpoint.getPath().matches("/v1beta/models/[A-Za-z0-9._-]+:generateContent")
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Chat Worker endpoint 须为不含凭据的 HTTPS Gemini generateContent 地址");
        }
        return endpoint;
    }

    /**
     * 已校验的 Chat Worker 运行配置快照。
     * 注意：此处只保存密钥的环境变量名 {@code keyEnvironment}，实际密钥在启动阶段调用 {@link #apiKey()} 时再读取，
     * 避免将明文密钥长期暴露在静态配置结构中。
     */
    record Settings(ApiFormat apiFormat, URI endpoint, String model, String keyEnvironment,
                    HttpClient.Version httpVersion, InetSocketAddress proxy, Duration connectTimeout,
                    Duration requestTimeout, int maxOutputTokens, String thinkingLevel, boolean enableThinking,
                    Master.ThinkingFormat thinkingFormat, String replyPreferences, int historyBudget) {
        /** 构造供 Chat Worker 使用的 HTTP 客户端；配置代理时走显式 HTTP 代理，未配置时直连端点。 */
        HttpClient newClient() {
            var builder = HttpClient.newBuilder().version(httpVersion).connectTimeout(connectTimeout);
            if (proxy != null) {
                builder.proxy(ProxySelector.of(proxy));
            }
            return builder.build();
        }

        /** 从当前进程环境变量解析 API 密钥。 */
        String apiKey() {
            return apiKey(System::getenv);
        }

        /** 从指定环境变量提供器解析 API 密钥；未配置密钥变量（免鉴权本机端点）时返回 null。 */
        String apiKey(Function<String, String> env) {
            if (keyEnvironment == null) {
                return null;
            }
            String key = env.apply(keyEnvironment);
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("缺少 Chat Worker 密钥环境变量 " + keyEnvironment);
            }
            return key;
        }

        /** 根据已校验的协议类型创建对应的 {@link ChatWorker} 实例，并注入角色提示词与偏好摘要。 */
        ChatWorker create(HttpClient client, String apiKey, String rolePrompt) {
            return new ChatWorker(apiFormat, client, endpoint, httpVersion, model, requestTimeout, maxOutputTokens,
                    thinkingLevel, enableThinking, thinkingFormat, apiKey, rolePrompt, replyPreferences, historyBudget);
        }
    }
}
