package neuralcloud.controlplane.dreaming;

import com.google.gson.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 整理器的发前日志与内部 Markdown 投影。文件可重建，Memory v2 始终拥有档案版本。 */
final class DreamingFiles implements AutoCloseable {
    private final Path output;
    private final String prefix;
    private final String marker;
    private final Connection db;
    private final FileChannel lock;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    DreamingFiles(Path audit, Path output, String character) throws IOException {
        this.output = output;
        prefix = "dream-" + character + "-";
        marker = "\n<!-- dreaming-person " + character + " ";
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

    private record Page(String id, String body) {}

    /** 多个角色可共用输出目录。锁覆盖认领、原子发布与清理，避免两次导出同时认领同一称呼。 */
    void publish(JsonArray profiles) throws IOException {
        Files.createDirectories(output);
        try (var channel = FileChannel.open(output.resolve(".dreaming-publish.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var publishing = channel.tryLock()) {
            if (publishing == null) throw new IOException("另一个 Dreaming 正在发布印象");
            publishLocked(profiles);
        } catch (OverlappingFileLockException e) {
            throw new IOException("另一个 Dreaming 正在发布印象", e);
        }
    }

    private void publishLocked(JsonArray profiles) throws IOException {
        var people = new LinkedHashMap<String, Page>();
        var topics = new LinkedHashMap<String, Page>();
        var names = DreamingNames.people(profiles.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(p -> "person".equals(p.get("kind").getAsString()))
                .map(p -> p.get("subject").getAsString()).toList());
        for (var entry : profiles) {
            var p = entry.getAsJsonObject();
            String kind = p.get("kind").getAsString(), name = p.get("subject").getAsString();
            if (!"DREAMING".equals(p.get("author").getAsString()))
                throw new IOException("Dreaming 档案署名无效");
            String id = DreamingNames.digest(name);
            String body = markdown(p, name);
            switch (kind) {
                case "person" -> {
                    String filename = names.get(name);
                    String tag = marker + id + " " + DreamingNames.filenameDigest(filename) + " -->\n";
                    people.put(filename, new Page(id, body + tag));
                }
                case "topic" -> topics.put(prefix + id + ".md", new Page(id, body));
                default -> throw new IOException("Dreaming 档案 kind 无效");
            }
        }
        var conflicts = new ArrayList<Path>();
        sync("people", people, conflicts);
        sync("topics", topics, conflicts);
        if (!conflicts.isEmpty()) throw new IOException("Dreaming 文件名已被占用：" + conflicts);
    }

    /** 正文、范围、原始依据与版本都取自同一个权威档案。 */
    private static String markdown(JsonObject p, String name) {
        var body = new StringBuilder("# ").append(name)
                .append("\n\n").append(p.get("text").getAsString())
                .append("\n\n## 适用范围\n\n").append(p.get("scope").getAsString())
                .append("\n\n## 修订理由\n\n").append(p.get("reason").getAsString())
                .append("\n\n## 一手依据\n\n");
        for (var raw : p.getAsJsonArray("source_refs")) {
            var ref = raw.getAsJsonObject();
            body.append("- ").append(ref.get("record_id").getAsString())
                    .append(" · 修订 ").append(ref.get("revision").getAsLong()).append('\n');
        }
        return body.append("\nDREAMING · 档案版本 ").append(p.get("version").getAsLong()).append('\n').toString();
    }

    /** 先撤回失效内容。仍有效的旧路径在新文件发布后清理，遇到手稿占名时可安全重试。 */
    private void sync(String group, Map<String, Page> pages, List<Path> conflicts) throws IOException {
        Path dir = Files.createDirectories(output.resolve(group));
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dreaming 导出目录无效 " + dir);
        var managed = new LinkedHashMap<Path, String>();
        var active = new HashSet<String>();
        for (var page : pages.values()) active.add(page.id());
        try (var entries = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : entries) {
                String id = owner(file, group.equals("people"));
                if (id == null) continue;
                if (active.contains(id)) managed.put(file, id);
                else Files.delete(file);
            }
        }
        var published = new HashSet<String>();
        for (var entry : pages.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            Page page = entry.getValue();
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            if (exists && !page.id().equals(managed.get(file))) {
                conflicts.add(file);
                continue;
            }
            byte[] content = page.body().getBytes(StandardCharsets.UTF_8);
            if (!exists || !Arrays.equals(Files.readAllBytes(file), content)) write(file, content, exists);
            managed.remove(file);
            published.add(page.id());
        }
        for (var former : managed.entrySet())
            if (published.contains(former.getValue())) Files.delete(former.getKey());
    }

    /** 普通名称靠末尾的角色标记认领。旧哈希文件还须匹配原标题和 Dreaming 版本行。 */
    private String owner(Path file, boolean person) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        String name = file.getFileName().toString();
        if (name.startsWith(prefix) && name.substring(prefix.length()).matches("[0-9a-f]{64}\\.md")) {
            String id = name.substring(prefix.length(), name.length() - 3);
            String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = body.indexOf("\n\n");
            if (body.startsWith("# ") && end >= 2 && id.equals(DreamingNames.digest(body.substring(2, end)))
                    && body.substring(body.lastIndexOf("\nDREAMING") + 1).matches("DREAMING · 档案版本 [1-9][0-9]*\\n"))
                return id;
        }
        if (!person) return null;
        // 只读固定长度的 ASCII 页脚，手写 Markdown 的编码和大小不影响所有权判断。
        int length = marker.length() + 64 + 1 + 64 + " -->\n".length();
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() < length) return null;
            channel.position(channel.size() - length);
            var buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null;
            String tail = new String(buffer.array(), StandardCharsets.US_ASCII);
            // 文件名也参与认领，用户另存的副本即使保留了原页脚也属于用户。
            if (!tail.startsWith(marker) || !tail.endsWith(" " + DreamingNames.filenameDigest(name) + " -->\n"))
                return null;
            String id = tail.substring(marker.length(), marker.length() + 64);
            return id.matches("[0-9a-f]{64}") ? id : null;
        }
    }

    private static void write(Path file, byte[] content, boolean exists) throws IOException {
        var temp = Files.createTempFile(file.getParent(), ".dreaming-", ".tmp");
        try {
            Files.write(temp, content);
            if (exists) Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
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
