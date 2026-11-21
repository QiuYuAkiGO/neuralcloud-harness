package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

public final class Main {
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8000/v1/completions";
    private static final String DEFAULT_MODEL = "neuralcloud-master-base";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        URI endpoint;
        String model;
        try {
            if (args.length != 0 && args.length != 2) {
                throw new IllegalArgumentException("请同时提供 <推理端点> <模型标识>，或省略两者");
            }
            endpoint = parseEndpoint(args.length == 0 ? DEFAULT_ENDPOINT : args[0]);
            model = args.length == 0 ? DEFAULT_MODEL : args[1];
            if (model.isBlank()) {
                throw new IllegalArgumentException("模型标识不能为空");
            }
        } catch (IllegalArgumentException error) {
            System.err.println("配置阶段失败：" + error.getMessage());
            return 2;
        }

        System.out.println("配置已校验，正在启动 Master：" + model);
        // 启动入口负责组装和释放资源。只有推理端点完成一次续写，才报告 Master 模型接入通过。
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            new Master(client, endpoint, model).probeStartup();
            System.out.println("Master 启动试验通过，模型已完成续写：" + model);
            return 0;
        } catch (IOException error) {
            System.err.println("Master 启动失败 [" + endpoint + "]：" + error.getMessage());
            return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Master 启动被中断");
            return 1;
        }
    }

    private static URI parseEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("推理端点不是有效的 URI");
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !endpoint.getPath().endsWith("/v1/completions")) {
            throw new IllegalArgumentException("推理端点须为以 /v1/completions 结尾且无凭据、查询或片段的 HTTP(S) URL");
        }
        return endpoint;
    }
}
