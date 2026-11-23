package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * 独立 Master 节点的命令行启动探测入口。
 * 职责：解析并校验 Master 配置、构造 HTTP 客户端并向推理端点发起一次启动探测，
 * 根据执行结果返回明确的进程退出码（0 通过、1 推理/中断失败、2 配置非法）。
 */
public final class Main {
    private static final ConfigSource DEFAULT_CONFIG = ConfigSource.bundled("master.properties");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * 执行完整的“配置校验 -> 组装 Master -> 启动探测 -> 资源回收”流程。
     * 退出码约定：0 表示配置与推理探测均通过；2 表示配置阶段失败；1 表示网络、契约或中断失败。
     */
    private static int run(String[] args) {
        Master.Settings settings;
        try {
            settings = MasterConfig.load(configSource(args, DEFAULT_CONFIG));
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        // 启动入口负责组装和释放资源。只有推理端点返回探测正文，才报告 Master 模型接入通过。
        try (HttpClient client = settings.newClient()) {
            var master = new Master(client, settings);
            System.out.println("配置已校验，正在启动 Master：" + settings.model());
            master.probeStartup();
            System.out.println("Master 启动试验通过，模型已返回探测正文：" + settings.model());
            return 0;
        } catch (IOException error) {
            System.err.println("Master 启动失败 [" + settings.endpoint() + "]");
            error.printStackTrace(System.err);
            return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Master 启动被中断");
            return 1;
        }
    }

    /**
     * 解析命令行参数确定配置来源。
     * 无参数时回退到默认内置配置；传入 {@code --config <路径>} 时指向外部文件，其他参数组合一律拒绝。
     */
    static ConfigSource configSource(String[] args, ConfigSource defaultConfig) {
        if (args.length == 0) {
            return defaultConfig;
        }
        if (args.length == 2 && args[0].equals("--config") && !args[1].isBlank()) {
            return ConfigSource.file(Path.of(args[1]));
        }
        throw new IllegalArgumentException("指定配置文件时请使用 --config <文件路径>");
    }
}
