package neuralcloud.controlplane.app;

import neuralcloud.controlplane.console.NodeMonitor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

final class ConsoleConfig {
    private static final Set<String> FIELDS = Set.of("listenHost", "listenPort", "auditDirectory",
            "chatWorkerConfig", "chatWorkerRolePrompt", "routerConfig", "routerRolePrompt", "routerMetrics",
            "routerMetricsFormat", "masterConfig", "masterMetrics", "masterMetricsFormat", "masterMaxTokens",
            "masterTemperature", "gpuMetrics", "metricsIntervalSeconds", "metricsTimeoutSeconds");

    private ConsoleConfig() {
    }

    record Settings(InetSocketAddress listen, Path auditDirectory,
                    ConfigSource chatWorkerConfig, ConfigSource chatWorkerRolePrompt,
                    ConfigSource routerConfig, ConfigSource routerRolePrompt, NodeMonitor.Metrics routerMetrics,
                    ConfigSource masterConfig, NodeMonitor.Metrics masterMetrics, int masterMaxTokens, double masterTemperature,
                    NodeMonitor.GpuSource gpuMetrics, Duration metricsInterval, Duration metricsTimeout,
                    Optional<ConfigSource> taskWorkerConfig) {
    }

    // 控制台没有身份校验，只允许监听本机回环地址。各节点的模型配置沿用各自的配置文件和校验。
    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        var required = new HashSet<>(properties.stringPropertyNames());
        required.remove("taskWorkerConfig");
        if (!required.equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("控制台配置须包含以下非空字段 " + FIELDS);
        }
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
                new ConfigSource(properties.getProperty("routerConfig")), new ConfigSource(properties.getProperty("routerRolePrompt")),
                metrics(properties, "routerMetrics"), new ConfigSource(properties.getProperty("masterConfig")),
                metrics(properties, "masterMetrics"), positive(properties, "masterMaxTokens"), temperature,
                enumValue(NodeMonitor.GpuSource.class, properties, "gpuMetrics"),
                Duration.ofSeconds(positive(properties, "metricsIntervalSeconds")),
                Duration.ofSeconds(positive(properties, "metricsTimeoutSeconds")),
                Optional.ofNullable(properties.getProperty("taskWorkerConfig")).map(ConfigSource::new));
    }

    private static NodeMonitor.Metrics metrics(Properties properties, String field) {
        URI endpoint = URI.create(properties.getProperty(field));
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
                || !"/metrics".equals(endpoint.getPath()) || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException(field + " 须为不含凭据、查询或片段的 HTTP(S) /metrics 地址");
        }
        return new NodeMonitor.Metrics(endpoint,
                enumValue(NodeMonitor.MetricsFormat.class, properties, field + "Format"));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Properties properties, String field) {
        try {
            return Enum.valueOf(type, properties.getProperty(field));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " 须为 " + Arrays.toString(type.getEnumConstants()), error);
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
