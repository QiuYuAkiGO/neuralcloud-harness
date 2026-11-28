package neuralcloud.controlplane.app;

import neuralcloud.controlplane.console.NodeMonitor;
import neuralcloud.controlplane.memory.ChatReview;
import neuralcloud.controlplane.orchestration.MasterEpochs;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 网页控制台总配置文件（{@code console.properties}）的解析与校验器。
 * 职责：聚合控制台监听地址、审计目录、各模型节点子配置文件路径、监控指标采集配置，
 * 以及可选的任务 Worker、OpenQQ 渠道与记忆服务配置路径，并在入口边界强制校验安全与格式契约。
 */
final class ConsoleConfig {
    /** 控制台必填基础配置键集合（可选子模块和成对可选的指标端点在比对前剔除）。 */
    private static final Set<String> FIELDS = Set.of("listenHost", "listenPort", "auditDirectory",
            "chatWorkerConfig", "chatWorkerRolePrompt", "routerConfig", "routerRolePrompt",
            "masterConfig", "masterMaxTokens", "masterTemperature",
            "gpuMetrics", "metricsIntervalSeconds", "metricsTimeoutSeconds");

    private ConsoleConfig() {
    }

    /**
     * 已校验的控制台聚合配置快照。
     * 子节点（Chat Worker、Router、Master、Task Worker、OpenQQ、Memory）的内部细节仍交由各自配置类加载校验。
     */
    record Settings(InetSocketAddress listen, Path auditDirectory,
                    ConfigSource chatWorkerConfig, ConfigSource chatWorkerRolePrompt,
                    ConfigSource routerConfig, ConfigSource routerRolePrompt,
                    Optional<NodeMonitor.Metrics> routerMetrics,
                    ConfigSource masterConfig, Optional<NodeMonitor.Metrics> masterMetrics,
                    int masterMaxTokens, double masterTemperature, MasterEpochs.Profile masterEpoch,
                    NodeMonitor.GpuSource gpuMetrics, Duration metricsInterval, Duration metricsTimeout,
                    Optional<ConfigSource> taskWorkerConfig, Optional<ConfigSource> openQqConfig,
                    Optional<ConfigSource> memoryConfig, Optional<ConfigSource> dreamingConfig,
                    Set<ChatReview> chatReviews) {
    }

    /**
     * 读取并校验控制台总配置。
     * 安全约束：控制台无身份认证，硬性要求 {@code listenHost} 为本机回环地址；
     * 云端推理服务通常不提供 Prometheus {@code /metrics} 端点，因此 {@code routerMetrics} 与 {@code masterMetrics} 均为成对可选配置。
     */
    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        // 按配置逐项启用。消费可选键后，剩余字段仍沿用控制台的原始契约。
        var chatReviews = EnumSet.noneOf(ChatReview.class);
        for (var stage : ChatReview.values()) {
            if (properties.containsKey(stage.property()) && MasterConfig.booleanField(properties, stage.property()))
                chatReviews.add(stage);
        }
        // 先剔除允许省略的可选字段，确保必填集合精确匹配且无未知拼写项。
        var required = new HashSet<>(properties.stringPropertyNames());
        required.remove("routerMetrics");
        required.remove("routerMetricsFormat");
        required.remove("masterMetrics");
        required.remove("masterMetricsFormat");
        required.remove("taskWorkerConfig");
        required.remove("openQqConfig");
        required.remove("memoryConfig");
        required.remove("dreamingConfig");
        required.remove("masterEpochWorkingBytes");
        required.remove("masterEpochTriggerBytes");
        if (!required.equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("控制台配置须包含以下非空字段 " + FIELDS);
        }
        // 拒绝绑定非回环地址，避免未鉴权控制台暴露到局域网或公网。
        InetAddress host = InetAddress.getByName(properties.getProperty("listenHost"));
        if (!host.isLoopbackAddress()) {
            throw new IllegalArgumentException("listenHost 须为本机回环地址");
        }
        int port = positive(properties, "listenPort");
        if (port > 65535) {
            throw new IllegalArgumentException("listenPort 须在 1 至 65535 之间");
        }
        double temperature;
        try {
            temperature = Double.parseDouble(properties.getProperty("masterTemperature"));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("masterTemperature 须为非负有限数值", error);
        }
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("masterTemperature 须为非负有限数值");
        }
        return new Settings(new InetSocketAddress(host, port), Path.of(properties.getProperty("auditDirectory")),
                new ConfigSource(properties.getProperty("chatWorkerConfig")),
                new ConfigSource(properties.getProperty("chatWorkerRolePrompt")),
                new ConfigSource(properties.getProperty("routerConfig")),
                new ConfigSource(properties.getProperty("routerRolePrompt")),
                optionalMetrics(properties, "routerMetrics"),
                new ConfigSource(properties.getProperty("masterConfig")),
                optionalMetrics(properties, "masterMetrics"),
                positive(properties, "masterMaxTokens"), temperature, epochProfile(properties),
                enumValue(NodeMonitor.GpuSource.class, properties, "gpuMetrics"),
                Duration.ofSeconds(positive(properties, "metricsIntervalSeconds")),
                Duration.ofSeconds(positive(properties, "metricsTimeoutSeconds")),
                Optional.ofNullable(properties.getProperty("taskWorkerConfig")).map(ConfigSource::new),
                Optional.ofNullable(properties.getProperty("openQqConfig")).map(ConfigSource::new),
                Optional.ofNullable(properties.getProperty("memoryConfig")).map(ConfigSource::new),
                Optional.ofNullable(properties.getProperty("dreamingConfig")).map(ConfigSource::new), Set.copyOf(chatReviews));
    }

    /** 旧配置使用首版小窗水位。自定义时须成对声明，按模型档位同时选择工作水位和触发水位。 */
    private static MasterEpochs.Profile epochProfile(Properties properties) {
        boolean working = properties.containsKey("masterEpochWorkingBytes");
        boolean trigger = properties.containsKey("masterEpochTriggerBytes");
        if (working != trigger) throw new IllegalArgumentException("masterEpochWorkingBytes 与 masterEpochTriggerBytes 须成对配置");
        return working ? new MasterEpochs.Profile(positive(properties, "masterEpochWorkingBytes"),
                positive(properties, "masterEpochTriggerBytes")) : MasterEpochs.Profile.DEFAULT;
    }

    /**
     * 解析成对出现的 Prometheus 指标端点与格式（如 {@code routerMetrics} + {@code routerMetricsFormat}）。
     * 二者同时省略表示该节点不采集 Prometheus 指标；仅配置其一时直接拒绝。
     */
    private static Optional<NodeMonitor.Metrics> optionalMetrics(Properties properties, String field) {
        String formatField = field + "Format";
        boolean hasEndpoint = properties.containsKey(field);
        boolean hasFormat = properties.containsKey(formatField);
        if (!hasEndpoint && !hasFormat) {
            return Optional.empty();
        }
        if (hasEndpoint != hasFormat) {
            throw new IllegalArgumentException(field + " 与 " + formatField + " 须成对配置");
        }
        URI endpoint = URI.create(properties.getProperty(field));
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
                || !"/metrics".equals(endpoint.getPath()) || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException(field + " 须为不含凭据、查询或片段的 HTTP(S) /metrics 地址");
        }
        return Optional.of(new NodeMonitor.Metrics(endpoint,
                enumValue(NodeMonitor.MetricsFormat.class, properties, formatField)));
    }

    /** 按枚举类型严格解析字段值，非法值直接列出允许的枚举项。 */
    private static <E extends Enum<E>> E enumValue(Class<E> type, Properties properties, String field) {
        try {
            return Enum.valueOf(type, properties.getProperty(field));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " 须为 " + Arrays.toString(type.getEnumConstants()), error);
        }
    }

    /** 解析必填正整数配置项。 */
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
