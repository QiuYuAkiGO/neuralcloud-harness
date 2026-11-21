package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.evaluation.MasterEvaluation;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
    private static final Path DEFAULT_CONFIG = Path.of("java", "src", "java", "resources", "master.properties");
    private static final Path MASTER_ROLE_PROMPT = DEFAULT_CONFIG.resolveSibling("master-role-prompt.txt");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        Master.Settings settings;
        Path fixturesPath = null;
        Path tracePath = null;
        try {
            if (args.length > 0 && args[0].equals("--master-eval")) {
                // 挡板场景由调用方选择，正式入口在常规启动时无需测试资源。
                if (args.length < 3 || args[1].isBlank() || args[1].startsWith("--")
                        || args[2].isBlank() || args[2].startsWith("--")) {
                    throw new IllegalArgumentException("请使用 --master-eval <样例 JSON 路径> <新的轨迹 JSONL 路径>");
                }
                fixturesPath = Path.of(args[1]);
                tracePath = Path.of(args[2]);
                args = Arrays.copyOfRange(args, 3, args.length);
            }
            settings = MasterConfig.load(configPath(args));
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        // 启动入口负责组装和释放资源。只有推理端点完成一次续写，才报告 Master 模型接入通过。
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build()) {
            var master = new Master(client, settings);
            if (tracePath != null) {
                return new MasterEvaluation(master, fixturesPath, Files.readString(MASTER_ROLE_PROMPT))
                        .run(tracePath, settings);
            }
            System.out.println("配置已校验，正在启动 Master：" + settings.model());
            master.probeStartup();
            System.out.println("Master 启动试验通过，模型已完成续写：" + settings.model());
            return 0;
        } catch (IOException error) {
            String operation = tracePath == null ? "启动" : "挡板运行";
            System.err.println("Master " + operation + "失败 [" + settings.endpoint() + "]：" + error.getMessage());
            return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Master 启动被中断");
            return 1;
        }
    }

    private static Path configPath(String[] args) {
        if (args.length == 0) {
            return DEFAULT_CONFIG;
        }
        if (args.length == 2 && args[0].equals("--config") && !args[1].isBlank()) {
            return Path.of(args[1]);
        }
        throw new IllegalArgumentException("指定配置文件时请使用 --config <文件路径>");
    }
}
