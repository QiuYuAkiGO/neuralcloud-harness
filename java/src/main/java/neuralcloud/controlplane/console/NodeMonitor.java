package neuralcloud.controlplane.console;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点运行状态。定时读取推理服务的 Prometheus 指标和本机 GPU 读数；最近一次调用与探测由控制台接口写入。
 * 指标端点不可达是可观察的运行状态，记录为失败读数，下一轮定时读取再更新。
 */
public final class NodeMonitor implements AutoCloseable {
    public enum MetricsFormat {
        VLLM("vllm:num_requests_running", "vllm:num_requests_waiting", "vllm:kv_cache_usage_perc",
                "vllm:gpu_cache_usage_perc", "vllm:num_preemptions_total", "vllm:prompt_tokens_total",
                "vllm:generation_tokens_total"),
        LLAMA_CPP("llamacpp:requests_processing", "llamacpp:requests_deferred", "llamacpp:kv_cache_usage_ratio",
                "llamacpp:prompt_tokens_total", "llamacpp:tokens_predicted_total");

        private final Set<String> names;

        MetricsFormat(String... names) {
            this.names = Set.of(names);
        }
    }

    public enum GpuSource { NVIDIA_SMI, NONE }

    public record Metrics(URI endpoint, MetricsFormat format) {
    }

    public record Outcome(String at, boolean ok, long latencyMs, String detail) {
    }

    // 指标值保留服务端原文，NaN、+Inf 等取值不做换算。
    public record Sample(String name, String labels, String value) {
    }

    public record Reading<T>(String at, boolean ok, List<T> items, String detail) {
    }

    public record Status(Metrics metrics, Reading<Sample> metricsReading, Outcome lastCall, Outcome lastProbe) {
    }

    private static final List<String> GPU_FIELDS = List.of(
            "index", "name", "memory.used", "memory.total", "utilization.gpu", "power.draw");

    private final HttpClient client;
    private final Duration timeout;
    private final GpuSource gpuSource;
    private final Map<String, Metrics> metrics;
    private final Map<String, Reading<Sample>> readings = new ConcurrentHashMap<>();
    private final Map<String, Outcome> calls = new ConcurrentHashMap<>();
    private final Map<String, Outcome> probes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "node-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Reading<Map<String, String>> gpu;

    // metrics 只包含提供指标端点的节点；Chat Worker 的远端服务不提供指标，只显示最近一次调用。
    public NodeMonitor(HttpClient client, Duration timeout, GpuSource gpuSource, Map<String, Metrics> metrics) {
        this.client = client;
        this.timeout = timeout;
        this.gpuSource = gpuSource;
        this.metrics = Map.copyOf(metrics);
    }

    public void start(Duration interval) {
        scheduler.scheduleWithFixedDelay(this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void recordCall(String node, Outcome outcome) {
        calls.put(node, outcome);
    }

    public void recordProbe(String node, Outcome outcome) {
        probes.put(node, outcome);
    }

    public Status status(String node) {
        return new Status(metrics.get(node), readings.get(node), calls.get(node), probes.get(node));
    }

    public Reading<Map<String, String>> gpu() {
        return gpu;
    }

    private void refresh() {
        metrics.forEach((node, source) -> readings.put(node, read(source)));
        if (gpuSource == GpuSource.NVIDIA_SMI) {
            gpu = readGpu();
        }
    }

    private Reading<Sample> read(Metrics source) {
        var request = HttpRequest.newBuilder(source.endpoint()).timeout(timeout).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return failed("指标端点返回 HTTP " + response.statusCode());
            }
            return new Reading<>(Instant.now().toString(), true, samples(response.body(), source.format()), "");
        } catch (IOException error) {
            return failed("指标端点不可达（" + ConsoleServer.describe(error) + "）");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed("指标读取被中断");
        }
    }

    // Prometheus 文本格式：名称、可选的 {标签}、取值、可选的时间戳。只保留该服务格式关注的指标。
    static List<Sample> samples(String body, MetricsFormat format) {
        var samples = new ArrayList<Sample>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int braces = line.indexOf('{');
            int space = line.indexOf(' ');
            int nameEnd = braces >= 0 && braces < space ? braces : space;
            if (nameEnd <= 0 || !format.names.contains(line.substring(0, nameEnd))) {
                continue;
            }
            String labels = "";
            String rest = line.substring(nameEnd);
            if (nameEnd == braces) {
                int close = line.lastIndexOf('}');
                labels = line.substring(braces + 1, close);
                rest = line.substring(close + 1);
            }
            samples.add(new Sample(line.substring(0, nameEnd), labels, rest.strip().split("\\s+")[0]));
        }
        return List.copyOf(samples);
    }

    private Reading<Map<String, String>> readGpu() {
        try {
            var process = new ProcessBuilder("nvidia-smi", "--query-gpu=" + String.join(",", GPU_FIELDS),
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return failed("nvidia-smi 未在超时内返回");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return failed("nvidia-smi 退出码 " + process.exitValue() + "：" + output);
            }
            var devices = new ArrayList<Map<String, String>>();
            for (String line : output.split("\\R")) {
                String[] values = line.split(",\\s*", -1);
                if (values.length != GPU_FIELDS.size()) {
                    return failed("nvidia-smi 输出列数与查询字段不符：" + line);
                }
                var device = new LinkedHashMap<String, String>();
                for (int index = 0; index < values.length; index++) {
                    device.put(GPU_FIELDS.get(index), values[index].strip());
                }
                devices.add(device);
            }
            return new Reading<>(Instant.now().toString(), true, List.copyOf(devices), "");
        } catch (IOException error) {
            return failed("无法运行 nvidia-smi（" + ConsoleServer.describe(error) + "）");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed("GPU 读数被中断");
        }
    }

    private static <T> Reading<T> failed(String detail) {
        return new Reading<>(Instant.now().toString(), false, List.of(), detail);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
