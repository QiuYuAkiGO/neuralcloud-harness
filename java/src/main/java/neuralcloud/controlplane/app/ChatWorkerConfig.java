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

final class ChatWorkerConfig {
    private ChatWorkerConfig() {}

    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        ApiFormat format = apiFormat(properties);
        HttpClient.Version httpVersion = MasterConfig.httpVersion(MasterConfig.required(properties, "httpVersion"));
        InetSocketAddress proxy = MasterConfig.optionalProxy(properties, "Chat Worker");
        Duration connectTimeout = Duration.ofSeconds(MasterConfig.positiveInteger(properties, "connectTimeoutSeconds"));
        Duration requestTimeout = Duration.ofSeconds(MasterConfig.positiveInteger(properties, "requestTimeoutSeconds"));
        int maxOutputTokens = MasterConfig.positiveInteger(properties, "maxOutputTokens");
        String preferences = new ConfigSource(MasterConfig.required(properties, "replyPreferences")).readText();
        if (preferences.isBlank()) {
            throw new IllegalArgumentException("replyPreferences 须指向非空的只读偏好摘要");
        }

        Settings settings;
        if (format == ApiFormat.GEMINI) {
            URI endpoint = geminiEndpoint(MasterConfig.required(properties, "endpoint"));
            String keyEnvironment = MasterConfig.required(properties, "apiKeyEnv");
            String level = MasterConfig.required(properties, "thinkingLevel");
            if (!List.of("MINIMAL", "LOW", "MEDIUM", "HIGH").contains(level)) {
                throw new IllegalArgumentException("Chat Worker thinkingLevel 须为 MINIMAL、LOW、MEDIUM 或 HIGH");
            }
            settings = new Settings(format, endpoint, null, keyEnvironment, httpVersion, proxy,
                    connectTimeout, requestTimeout, maxOutputTokens, level, false, null, preferences);
        } else {
            URI endpoint = MasterConfig.chatEndpoint(MasterConfig.required(properties, "endpoint"), "Chat Worker");
            String model = MasterConfig.required(properties, "model");
            String keyEnvironment = MasterConfig.optional(properties, "apiKeyEnv");
            boolean enableThinking = MasterConfig.booleanField(properties, "enableThinking");
            // OpenAI 兼容聊天端点默认沿用本机推理服务的 chat_template_kwargs，云端可显式选择 DEEPSEEK 或 NONE。
            Master.ThinkingFormat thinkingFormat = MasterConfig.optionalThinkingFormat(
                    properties, Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS);
            settings = new Settings(format, endpoint, model, keyEnvironment, httpVersion, proxy,
                    connectTimeout, requestTimeout, maxOutputTokens, null, enableThinking, thinkingFormat, preferences);
        }
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("Chat Worker 存在未知配置项 " + properties.stringPropertyNames());
        }
        return settings;
    }

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

    record Settings(ApiFormat apiFormat, URI endpoint, String model, String keyEnvironment,
                    HttpClient.Version httpVersion, InetSocketAddress proxy, Duration connectTimeout,
                    Duration requestTimeout, int maxOutputTokens, String thinkingLevel, boolean enableThinking,
                    Master.ThinkingFormat thinkingFormat, String replyPreferences) {
        // 配置代理时走显式 HTTP 代理，未配置时直连本机或云端端点。
        HttpClient newClient() {
            var builder = HttpClient.newBuilder().version(httpVersion).connectTimeout(connectTimeout);
            if (proxy != null) {
                builder.proxy(ProxySelector.of(proxy));
            }
            return builder.build();
        }

        String apiKey() {
            return apiKey(System::getenv);
        }

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

        ChatWorker create(HttpClient client, String apiKey, String rolePrompt) {
            if (apiFormat == ApiFormat.GEMINI) {
                return new ChatWorker(client, endpoint, requestTimeout, maxOutputTokens, thinkingLevel, apiKey,
                        rolePrompt, replyPreferences);
            }
            return ChatWorker.openAiChat(client, endpoint, httpVersion, model, apiKey, requestTimeout, maxOutputTokens,
                    enableThinking, thinkingFormat, rolePrompt, replyPreferences);
        }
    }
}
