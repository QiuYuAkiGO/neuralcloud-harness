package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;

public final class Main {
    private static final ConfigSource DEFAULT_CONFIG = ConfigSource.bundled("master.properties");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

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
