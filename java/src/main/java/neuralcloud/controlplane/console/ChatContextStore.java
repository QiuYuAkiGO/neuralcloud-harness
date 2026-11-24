package neuralcloud.controlplane.console;

import com.google.gson.JsonParseException;
import neuralcloud.controlplane.console.ChatSummary.State;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 审计目录内的单行派生快照，修订、受众、依据和覆盖点由同一条 SQLite 语句原子更新。 */
final class ChatContextStore implements AutoCloseable {
    record Head(long revision, State state) {}
    private final Path database;
    private Connection connection;

    ChatContextStore(Path directory) { database = directory.resolve("chat-context.sqlite"); }

    /** OFF 不打开数据库。每次运行首次读取时才建立可从原审计重建的表。 */
    private Connection open() throws SQLException {
        if (connection == null) {
            var opened = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            try (var statement = opened.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS chat_context(session TEXT PRIMARY KEY, revision INTEGER NOT NULL, snapshot TEXT NOT NULL)");
            } catch (SQLException e) {
                opened.close();
                throw e;
            }
            connection = opened;
        }
        return connection;
    }

    synchronized Head read(String session) throws IOException {
        try (var query = open().prepareStatement("SELECT revision,snapshot FROM chat_context WHERE session=?")) {
            query.setString(1, session);
            try (var row = query.executeQuery()) {
                if (!row.next()) return new Head(0, null);
                State state;
                try { state = ChatSummary.JSON.fromJson(row.getString(2), State.class); }
                catch (JsonParseException e) { state = null; }
                return new Head(row.getLong(1), state);
            }
        } catch (SQLException e) { throw new IOException("读取会话提要快照失败", e); }
    }

    /** 跨连接和跨进程都比较持久修订。若提交回执不明，调用方重新读库再决定本轮可投影的状态。 */
    synchronized boolean commit(String session, long revision, State next) throws IOException {
        String sql = revision == 0
                ? "INSERT INTO chat_context(snapshot,revision,session) VALUES(?,?,?) ON CONFLICT(session) DO NOTHING"
                : "UPDATE chat_context SET snapshot=?,revision=? WHERE session=? AND revision=?";
        try (var write = open().prepareStatement(sql)) {
            write.setString(1, ChatSummary.JSON.toJson(next));
            write.setLong(2, revision + 1);
            write.setString(3, session);
            if (revision > 0) write.setLong(4, revision);
            return write.executeUpdate() == 1;
        } catch (SQLException e) { throw new IOException("提交会话提要快照失败", e); }
    }

    @Override public synchronized void close() throws IOException {
        if (connection != null) try { connection.close(); }
        catch (SQLException e) { throw new IOException("关闭会话提要数据库失败", e); }
    }
}
