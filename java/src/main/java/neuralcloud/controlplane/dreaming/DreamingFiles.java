package neuralcloud.controlplane.dreaming;

import com.google.gson.*;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** 整理器的发前日志与内部 Markdown 投影。文件可重建，Memory v2 始终拥有档案版本。 */
final class DreamingFiles implements AutoCloseable {
    private final Path output;
    private final String prefix;
    private final Connection db;
    private final FileChannel lock;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    DreamingFiles(Path audit, Path output, String character) throws IOException {
        this.output = output;
        prefix = "dream-" + character + "-";
        lock =
                FileChannel.open(
                        audit.resolve("dreaming-" + character + ".lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            if (lock.tryLock() == null) throw new IOException("已有 Dreaming 正在使用该审计目录");
            db = open(audit.resolve("dreaming-" + character + ".sqlite"));
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    /** 单槽对应单个在途批次。SQLite 提交完成后才可向远端发出命令。 */
    private static Connection open(Path file) throws IOException {
        try {
            var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS pending(slot INTEGER PRIMARY KEY CHECK(slot=1),"
                            + " command TEXT NOT NULL)");
                return connection;
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("打开 Dreaming 发前日志失败", e);
        }
    }

    JsonObject pending() throws IOException {
        try (var s = db.createStatement();
                var row = s.executeQuery("SELECT command FROM pending WHERE slot=1")) {
            return row.next() ? JSON.fromJson(row.getString(1), JsonObject.class) : null;
        } catch (SQLException | JsonParseException e) {
            throw new IOException("读取 Dreaming 未决命令失败", e);
        }
    }

    void sending(JsonObject command) throws IOException {
        try (var s = db.prepareStatement("INSERT INTO pending VALUES(1,?)")) {
            s.setString(1, command.toString());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("登记 Dreaming 提交失败", e);
        }
    }

    void resolved() throws IOException {
        try (var s = db.createStatement()) {
            s.executeUpdate("DELETE FROM pending WHERE slot=1");
        } catch (SQLException e) {
            throw new IOException("确认 Dreaming 命令失败", e);
        }
    }

    /** 先清理已退出的本角色投影，再按档案原子替换。只管理宿主命名的文件。 */
    void publish(JsonArray profiles) throws IOException {
        var pages = new LinkedHashMap<Path, String>();
        for (var entry : profiles) {
            var p = entry.getAsJsonObject();
            String kind = p.get("kind").getAsString(), name = p.get("subject").getAsString();
            String group =
                    switch (kind) {
                        case "person" -> "people";
                        case "topic" -> "topics";
                        default -> throw new IOException("Dreaming 档案 kind 无效");
                    };
            if (!"DREAMING".equals(p.get("author").getAsString()))
                throw new IOException("Dreaming 档案署名无效");
            var body =
                    new StringBuilder("# ")
                            .append(name)
                            .append("\n\n")
                            .append(p.get("text").getAsString())
                            .append("\n\n## 适用范围\n\n")
                            .append(p.get("scope").getAsString())
                            .append("\n\n## 修订理由\n\n")
                            .append(p.get("reason").getAsString())
                            .append("\n\n## 一手依据\n\n");
            for (var raw : p.getAsJsonArray("source_refs")) {
                var ref = raw.getAsJsonObject();
                body.append("- ")
                        .append(ref.get("record_id").getAsString())
                        .append(" · 修订 ")
                        .append(ref.get("revision").getAsLong())
                        .append('\n');
            }
            body.append("\nDREAMING · 档案版本 ").append(p.get("version").getAsLong()).append('\n');
            pages.put(
                    output.resolve(group).resolve(prefix + digest(name) + ".md"), body.toString());
        }
        for (String group : List.of("people", "topics")) {
            var dir = Files.createDirectories(output.resolve(group));
            try (var entries = Files.newDirectoryStream(dir, prefix + "*.md")) {
                for (Path file : entries) {
                    String name = file.getFileName().toString();
                    if (name.substring(prefix.length()).matches("[0-9a-f]{64}\\.md")
                            && !pages.containsKey(file)) Files.delete(file);
                }
            }
        }
        for (var page : pages.entrySet()) {
            var file = page.getKey();
            byte[] content = page.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.exists(file) && Arrays.equals(Files.readAllBytes(file), content)) continue;
            var temp = Files.createTempFile(file.getParent(), ".dreaming-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IOException("关闭 Dreaming 发前日志失败", e);
        } finally {
            lock.close();
        }
    }
}
