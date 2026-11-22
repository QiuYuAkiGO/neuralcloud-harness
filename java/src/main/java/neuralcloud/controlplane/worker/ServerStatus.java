package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

public record ServerStatus(String observedAt, long uptimeSeconds, double loadOneMinute,
                           long memoryAvailableBytes, long memoryTotalBytes, long rootAvailableBytes,
                           long rootTotalBytes) {
    private static final Pattern MEMORY_TOTAL = Pattern.compile("(?m)^MemTotal:\\s+(\\d+) kB$");
    private static final Pattern MEMORY_AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+) kB$");

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

    public String measurements() {
        return String.format(Locale.ROOT, "已运行 %.1f 小时，一分钟负载 %.2f。"
                        + "内存可用 %.1f / %.1f GiB，根分区可用 %.1f / %.1f GiB。",
                uptimeSeconds / 3600.0, loadOneMinute, gib(memoryAvailableBytes), gib(memoryTotalBytes),
                gib(rootAvailableBytes), gib(rootTotalBytes));
    }

    private static double gib(long bytes) {
        return bytes / 1073741824.0;
    }
}
