package neuralcloud.controlplane.app;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 统一封装配置文件和提示词文本的来源位置。
 * 支持两类地址：
 * 1. {@code classpath:/...}：从打包进 JAR 的类路径资源中以 UTF-8 读取；
 * 2. 普通文件路径：按当前进程的工作目录或绝对路径从本地文件系统读取。
 */
record ConfigSource(String location) {
    private static final String CLASSPATH = "classpath:";

    /** 构造指向打包内置 {@code /config/<name>} 目录的资源来源。 */
    static ConfigSource bundled(String name) {
        return new ConfigSource(CLASSPATH + "/config/" + name);
    }

    /** 构造指向本地磁盘文件的资源来源，内部统一转为绝对路径以便报错时明确指向。 */
    static ConfigSource file(Path path) {
        return new ConfigSource(path.toAbsolutePath().toString());
    }

    /** 将来源内容按标准 Java {@link Properties} 格式解析并返回键值对。 */
    Properties readProperties() throws IOException {
        var properties = new Properties();
        try (var reader = open()) {
            properties.load(reader);
        }
        return properties;
    }

    /** 将来源内容完整读取为字符串，常用于加载角色提示词或偏好摘要等纯文本文件。 */
    String readText() throws IOException {
        try (var reader = open()) {
            var text = new StringWriter();
            reader.transferTo(text);
            return text.toString();
        }
    }

    /**
     * 打开底层字符流。
     * JAR 内资源通过类加载器流以严格 UTF-8 解码读取；普通文件路径交由操作系统按进程工作目录解析。
     */
    private Reader open() throws IOException {
        if (!location.startsWith(CLASSPATH)) {
            return Files.newBufferedReader(Path.of(location));
        }
        String resource = location.substring(CLASSPATH.length());
        if (!resource.startsWith("/")) {
            throw new IllegalArgumentException("类路径资源须使用 classpath:/ 开头：" + location);
        }
        var stream = ConfigSource.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("找不到类路径资源 " + location);
        }
        return new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder());
    }
}
