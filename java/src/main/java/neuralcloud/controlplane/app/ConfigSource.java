package neuralcloud.controlplane.app;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record ConfigSource(String location) {
    private static final String CLASSPATH = "classpath:";

    static ConfigSource bundled(String name) {
        return new ConfigSource(CLASSPATH + "/config/" + name);
    }

    static ConfigSource file(Path path) {
        return new ConfigSource(path.toAbsolutePath().toString());
    }

    Properties readProperties() throws IOException {
        var properties = new Properties();
        try (var reader = open()) {
            properties.load(reader);
        }
        return properties;
    }

    String readText() throws IOException {
        try (var reader = open()) {
            var text = new StringWriter();
            reader.transferTo(text);
            return text.toString();
        }
    }

    // JAR 内资源通过流读取。普通文件路径仍由进程的工作目录解析。
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
