package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 本机 Linux 宿主机运行状态的只读采样快照，作为任务 Worker 现场执行后的结构化回执载体。
 * 字段涵盖采样时间戳（ISO-8601）、运行时长（秒）、一分钟平均负载、内存可用/总字节数以及根分区可用/总字节数。
 */
public record ServerStatus(String observedAt, long uptimeSeconds, double loadOneMinute,
                           long memoryAvailableBytes, long memoryTotalBytes, long rootAvailableBytes,
                           long rootTotalBytes) {
    private static final Pattern MEMORY_TOTAL = Pattern.compile("(?m)^MemTotal:\\s+(\\d+) kB$");
    private static final Pattern MEMORY_AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+) kB$");

    /**
     * 直接读取本机 Linux 的 {@code /proc/uptime}、{@code /proc/meminfo}、系统一分钟负载与根分区磁盘容量。
     * 所有数值均来自操作系统实时只读接口；任一必要指标缺失或无法解析时抛出 {@link IOException}，不返回伪造默认值。
     */
    public static ServerStatus readLinux() throws IOException {
        String uptime = Files.readString(Path.of("/proc/uptime")).split(" ", 2)[0];
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        String memory = Files.readString(Path.of("/proc/meminfo"));
        var total = MEMORY_TOTAL.matcher(memory);
        var available = MEMORY_AVAILABLE.matcher(memory);
        if (load < 0 || !total.find() || !available.find()) {
            throw new IOException("Linux 状态采样缺少一分钟负载或内存字段");
        }
        var root = Files.getFileStore(Path.of("/"));
        try {
            return new ServerStatus(Instant.now().toString(), (long) Double.parseDouble(uptime), load,
                    Long.parseLong(available.group(1)) * 1024, Long.parseLong(total.group(1)) * 1024,
                    root.getUsableSpace(), root.getTotalSpace());
        } catch (NumberFormatException error) {
            throw new IOException("Linux 状态采样的运行时间或内存字段无效", error);
        }
    }

    /** 将采样快照格式化为固定中文测量报告，供工具回执落盘与 Chat Worker 附带呈现（数值不可由模型篡改）。 */
    public String measurements() {
        return String.format(Locale.ROOT, "已运行 %.1f 小时，一分钟负载 %.2f。"
                        + "内存可用 %.1f / %.1f GiB，根分区可用 %.1f / %.1f GiB。",
                uptimeSeconds / 3600.0, loadOneMinute, gib(memoryAvailableBytes), gib(memoryTotalBytes),
                gib(rootAvailableBytes), gib(rootTotalBytes));
    }

    /** 字节数按二进制 1024^3 换算为 GiB。 */
    private static double gib(long bytes) {
        return bytes / 1073741824.0;
    }
}
