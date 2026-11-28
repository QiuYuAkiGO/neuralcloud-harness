package neuralcloud.controlplane.console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 在显式配置审计文件的进程登记实际请求正文。密钥和网络请求头不属于模型输入。 */
public final class ModelInputLog {
    private static final String TARGET = System.getProperty("neuralcloud.modelInputLog");
    private ModelInputLog() {}

    public static synchronized String record(String json) throws IOException {
        if (TARGET != null) {
            Files.writeString(Path.of(TARGET), json + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return json;
    }
}
