package neuralcloud.controlplane.memory;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Harness owns this journal. A queued command survives loss of its HTTP response. Each delivered
 * item retains its own snapshot cursor, including registrations arriving after a change.
 */
public final class MemoryJournal implements AutoCloseable {
    private final Connection db;

    public MemoryJournal(Path path) throws IOException {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (var sql = db.createStatement()) {
                sql.execute("PRAGMA journal_mode=WAL");
                sql.execute("PRAGMA synchronous=FULL");
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS commands(event TEXT PRIMARY KEY,body TEXT NOT"
                            + " NULL,receipt TEXT,stream TEXT NOT NULL)");
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS inputs(input_id TEXT PRIMARY KEY,worker"
                            + " TEXT,scope TEXT,status TEXT,material TEXT)");
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS input_evidence(input_id TEXT,kind TEXT,id"
                            + " TEXT,stream TEXT,seq INTEGER,item TEXT,PRIMARY"
                            + " KEY(input_id,kind,id))");
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS watches(worker TEXT,scope TEXT,kind TEXT,id"
                            + " TEXT,stream TEXT,seq INTEGER,item TEXT,input_id TEXT,PRIMARY"
                            + " KEY(worker,scope,kind,id,input_id))");
            }
        } catch (SQLException e) {
            throw new IOException("无法打开 Harness 记忆日志", e);
        }
    }

    record Read(
            String worker,
            String scope,
            String kind,
            String id,
            String stream,
            long seq,
            JsonObject item,
            String input) {}

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        var p = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
        return p;
    }

    private void exec(String sql, Object... args) throws SQLException {
        try (var p = statement(sql, args)) {
            p.executeUpdate();
        }
    }

    synchronized void enqueue(String event, JsonObject body, String stream) throws IOException {
        try {
            exec(
                    "INSERT OR IGNORE INTO commands(event,body,stream) VALUES(?,?,?)",
                    event,
                    body.toString(),
                    stream);
            try (var p = statement("SELECT body,stream FROM commands WHERE event=?", event);
                    var r = p.executeQuery()) {
                if (!r.next()
                        || !JsonParser.parseString(r.getString(1)).equals(body)
                        || !stream.equals(r.getString(2))) throw new IOException("待写事件与已登记命令不一致");
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized List<JsonObject> pending(String stream) throws IOException {
        try (var p =
                        statement(
                                "SELECT body,stream FROM commands WHERE receipt IS NULL ORDER BY"
                                    + " rowid");
                var r = p.executeQuery()) {
            var list = new ArrayList<JsonObject>();
            while (r.next()) {
                if (!stream.equals(r.getString(2))) throw new IOException("待写命令属于另一条记忆流");
                list.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());
            }
            return list;
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized void ack(String event, JsonObject receipt) throws IOException {
        try {
            exec("UPDATE commands SET receipt=? WHERE event=?", receipt.toString(), event);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized List<String> recent(String scope, String stream, String before)
            throws IOException {
        try (var p =
                        statement(
                                """
SELECT json_extract(receipt,'$.record_id'),json_extract(body,'$.occurred_at') FROM commands
WHERE stream=? AND json_extract(receipt,'$.status')='DURABLE' AND json_extract(body,'$.context.conversation_id')=?
AND json_extract(body,'$.kind') IN ('observation','self_expression') ORDER BY rowid DESC""",
                                stream,
                                scope);
                var r = p.executeQuery()) {
            var upper = java.time.Instant.parse(before);
            var ids = new ArrayList<String>();
            while (ids.size() < 6 && r.next())
                if (java.time.Instant.parse(r.getString(2)).isBefore(upper))
                    ids.add(r.getString(1));
            return ids.reversed();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized List<Read> reads(String worker, String scope) throws IOException {
        try (var p =
                        statement(
                                "SELECT kind,id,stream,seq,item,input_id FROM watches WHERE"
                                    + " worker=? AND scope=? ORDER BY id,rowid",
                                worker,
                                scope);
                var r = p.executeQuery()) {
            var out = new ArrayList<Read>();
            while (r.next())
                out.add(
                        new Read(
                                worker,
                                scope,
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                r.getLong(4),
                                JsonParser.parseString(r.getString(5)).getAsJsonObject(),
                                r.getString(6)));
            return out;
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized void prepare(
            String input, String worker, String scope, String material, List<Read> bindings)
            throws IOException {
        try {
            db.setAutoCommit(false);
            try {
                exec(
                        "INSERT INTO inputs VALUES(?,?,?,'PREPARED',?)",
                        input,
                        worker,
                        scope,
                        material);
                for (var b : bindings) {
                    exec(
                            "INSERT INTO input_evidence VALUES(?,?,?,?,?,?)",
                            input,
                            b.kind(),
                            b.id(),
                            b.stream(),
                            b.seq(),
                            b.item().toString());
                    // A later-arriving input keeps its own read_seq even when an earlier input
                    // already advanced.
                    exec(
                            "INSERT INTO watches VALUES(?,?,?,?,?,?,?,?)",
                            worker,
                            scope,
                            b.kind(),
                            b.id(),
                            b.stream(),
                            b.seq(),
                            b.item().toString(),
                            input);
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized void delivered(String input) throws IOException {
        try {
            exec("UPDATE inputs SET status='DELIVERED' WHERE input_id=?", input);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    synchronized void finishRefresh(String input, List<Read> updates) throws IOException {
        try {
            db.setAutoCommit(false);
            try {
                exec("UPDATE inputs SET status='DELIVERED' WHERE input_id=?", input);
                for (var b : updates) {
                    exec(
                            """
UPDATE watches SET stream=?,seq=?,item=? WHERE worker=? AND scope=? AND kind=? AND id=?
AND input_id=? AND (stream<>? OR seq<=?)""",
                            b.stream(),
                            b.seq(),
                            b.item().toString(),
                            b.worker(),
                            b.scope(),
                            b.kind(),
                            b.id(),
                            b.input(),
                            b.stream(),
                            b.seq());
                }
                // Only coalesce subscriptions that have reached exactly the same evidence and
                // watermark.
                // The per-input evidence above stays immutable for audit and restart.
                if (!updates.isEmpty()) {
                    var owner = updates.getFirst();
                    exec(
                            """
DELETE FROM watches WHERE worker=? AND scope=? AND rowid NOT IN
(SELECT max(rowid) FROM watches WHERE worker=? AND scope=? GROUP BY kind,id,stream,seq,item)""",
                            owner.worker(),
                            owner.scope(),
                            owner.worker(),
                            owner.scope());
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
