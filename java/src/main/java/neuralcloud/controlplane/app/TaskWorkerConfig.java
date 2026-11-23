package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.worker.ServerStatus;
import neuralcloud.controlplane.worker.TaskWorker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * 任务 Worker（现场动作执行节点）的配置加载与工厂记录。
 * 当前首期挂载的现场能力为读取本机 Linux 服务器状态（{@link ServerStatus#readLinux()}），
 * 因此配置校验阶段即强约束宿主操作系统必须为 Linux，避免上线后才在执行期失败。
 */
record TaskWorkerConfig(URI endpoint, String model, String apiKey, HttpClient.Version httpVersion,
                        Master.ThinkingFormat thinkingFormat, InetSocketAddress proxy,
                        Duration connectTimeout, Duration requestTimeout) {
    /** 使用当前系统环境变量与操作系统名称加载任务 Worker 配置。 */
    static TaskWorkerConfig load(ConfigSource source) throws IOException {
        return load(source, System::getenv, System.getProperty("os.name"));
    }

    /**
     * 读取并校验任务 Worker 配置（允许测试注入环境变量与操作系统名）。
     * 超时字段使用 ISO 8601 时长格式（如 {@code PT10S}），且要求严格为正值。
     */
    static TaskWorkerConfig load(ConfigSource source, Function<String, String> env, String osName) throws IOException {
        var properties = source.readProperties();
        URI endpoint = MasterConfig.chatEndpoint(MasterConfig.required(properties, "endpoint"), "任务 Worker");
        String model = MasterConfig.required(properties, "model");
        String key = MasterConfig.optionalApiKey(properties, env, "任务 Worker");
        String versionValue = MasterConfig.optional(properties, "httpVersion");
        HttpClient.Version httpVersion = versionValue == null
                ? HttpClient.Version.HTTP_1_1 : MasterConfig.httpVersion(versionValue);
        // 兼容既有的 DeepSeek 配置：未指定 thinkingFormat 时默认使用 DEEPSEEK，本机服务可设为 CHAT_TEMPLATE_KWARGS 或 NONE。
        Master.ThinkingFormat thinkingFormat = MasterConfig.optionalThinkingFormat(
                properties, Master.ThinkingFormat.DEEPSEEK);
        InetSocketAddress proxy = MasterConfig.optionalProxy(properties, "任务 Worker");
        Duration connect;
        Duration request;
        try {
            connect = Duration.parse(MasterConfig.required(properties, "connectTimeout"));
            request = Duration.parse(MasterConfig.required(properties, "requestTimeout"));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("connectTimeout 和 requestTimeout 须为正的 ISO 8601 时长", error);
        }
        if (!connect.isPositive() || !request.isPositive()) {
            throw new IllegalArgumentException("connectTimeout 和 requestTimeout 须为正的 ISO 8601 时长");
        }
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException("任务 Worker 存在未知配置项 " + properties.stringPropertyNames());
        }
        // 任务 Worker 的服务器探测依赖 /proc 与 Linux 系统工具，非 Linux 宿主机在配置阶段即拒绝启用。
        if (!"Linux".equals(osName)) {
            throw new IllegalArgumentException("任务 Worker 的服务器状态读取能力需要 Linux");
        }
        return new TaskWorkerConfig(endpoint, model, key, httpVersion, thinkingFormat, proxy, connect, request);
    }

    /** 创建任务 Worker 专用的 HTTP 客户端（按需绑定代理和连接超时）。 */
    HttpClient newClient() {
        var builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
        }
        return builder.build();
    }

    /** 装配任务 Worker 实例，并绑定只读的 Linux 服务器状态采样函数。 */
    TaskWorker create(HttpClient client) {
        return new TaskWorker(client, endpoint, httpVersion, model, apiKey, thinkingFormat, requestTimeout,
                ServerStatus::readLinux);
    }
}
