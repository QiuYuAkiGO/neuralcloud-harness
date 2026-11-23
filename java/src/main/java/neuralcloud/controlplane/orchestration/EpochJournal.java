package neuralcloud.controlplane.orchestration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Master 主干的追加日志。最大代号拥有写租约，旧代的成员表即归档，分段原文只存一次。 */
final class EpochJournal implements AutoCloseable {
    private static final Gson JSON = new Gson();
    /** MATTER 由操作者登记，REQUEST 由入站自动创建；后续记录通过 matter 指回起始段号。 */
    enum Kind { MATTER, REQUEST, DECISION, FAILED, RECEIPT }

    /** 冻结时只交付段号、业务标签和正文大小。候选不持有正文、数据库连接或派单接口。 */
    record Label(long seq, Kind kind, boolean required, long bytes) {}
    record Snapshot(long epoch, long through, List<Label> labels) {}
    record Head(long epoch, long seq, long bytes) {}
    record Admission(long seq, boolean fresh) {}

    private final Connection db;
    private final long recoveredThrough; // 此水位以前的未决请求来自旧进程，允许同一受众按实际回执核销。

    /** 控制面在接收请求前独占该主体的运行日志。进程退出后由 SQLite 释放锁并恢复未提交的事务。 */
    static EpochJournal open(Path file) throws IOException {
        try {
            var db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (var sql = db.createStatement()) {
                sql.execute("PRAGMA locking_mode=EXCLUSIVE");
                sql.execute("PRAGMA synchronous=FULL");
                sql.execute("PRAGMA foreign_keys=ON");
                sql.execute("""
                        CREATE TABLE IF NOT EXISTS epoch(
                          id INTEGER PRIMARY KEY, frozen_seq INTEGER NOT NULL, through_seq INTEGER NOT NULL,
                          bytes INTEGER NOT NULL DEFAULT 0)""");
                sql.execute("INSERT OR IGNORE INTO epoch(id,frozen_seq,through_seq) VALUES(1,0,0)");
                sql.execute("""
                        CREATE TABLE IF NOT EXISTS segment(
                          seq INTEGER PRIMARY KEY, audience TEXT NOT NULL, origin TEXT NOT NULL,
                          kind TEXT NOT NULL, body TEXT NOT NULL, matter INTEGER REFERENCES segment(seq), checkpoint TEXT,
                          seen_epoch INTEGER NOT NULL DEFAULT 0, seen_seq INTEGER NOT NULL DEFAULT 0,
                          UNIQUE(origin,kind))""");
                sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS closed_matter ON segment(matter) WHERE kind='RECEIPT'");
                sql.execute("CREATE INDEX IF NOT EXISTS presented ON segment(audience,seen_epoch,seen_seq)");
                sql.execute("""
                        CREATE TABLE IF NOT EXISTS member(
                          epoch INTEGER REFERENCES epoch(id), seq INTEGER REFERENCES segment(seq), carried INTEGER NOT NULL DEFAULT 0,
                          PRIMARY KEY(epoch,seq))""");
                // 每次接收入站、裁决或回执都和它的主干归属一起提交，切换事务之间没有悬空事件。
                sql.execute("""
                        CREATE TRIGGER IF NOT EXISTS join_head AFTER INSERT ON segment BEGIN
                          INSERT INTO member(epoch,seq) VALUES((SELECT max(id) FROM epoch),NEW.seq);
                          UPDATE epoch SET bytes=bytes+length(CAST(NEW.body AS BLOB)) WHERE id=(SELECT max(id) FROM epoch);
                        END""");
                try (var rows = sql.executeQuery("SELECT COALESCE(max(seq),0) FROM segment")) {
                    rows.next();
                    return new EpochJournal(db, rows.getLong(1));
                }
            } catch (SQLException error) {
                db.close();
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("无法取得 Master Epoch 日志的独占写租约：" + file, error);
        }
    }

    private EpochJournal(Connection db, long recoveredThrough) {
        this.db = db;
        this.recoveredThrough = recoveredThrough;
    }

    private PreparedStatement statement(String sql, Object... values) throws SQLException {
        var statement = db.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }

    /** 入口按当前租约追加原事项。origin 来自已绑定的渠道事件，用于核对重复投递。 */
    synchronized Admission openMatter(String audience, String origin, Kind kind, String body) throws IOException {
        try (var p = statement("""
                INSERT INTO segment(audience,origin,kind,body) VALUES(?,?,?,?)
                ON CONFLICT(origin,kind) DO NOTHING RETURNING seq""",
                audience, origin, kind.name(), body); var rows = p.executeQuery()) {
            if (rows.next()) return new Admission(rows.getLong(1), true);
            try (var same = statement("SELECT seq FROM segment WHERE origin=? AND kind=? AND audience=? AND body=?",
                    origin, kind.name(), audience, body); var old = same.executeQuery()) {
                if (old.next()) return new Admission(old.getLong(1), false);
                throw new IOException("同一 Master 入站来源的受众或正文不一致");
            }
        } catch (SQLException error) {
            throw new IOException("无法登记 Master 事项", error);
        }
    }

    /** 唯一写租约提交裁决。自动派单由原工作流结项；已中断或重启恢复的事项可以按同受众的实际回执核销。 */
    synchronized void record(long lease, String audience, String origin, Kind kind, long matter, String body,
                             long seen, Object checkpoint)
            throws IOException {
        // 版本化证据和已验收意图保存在原单步的检查点中。上下文投影仅使用允许重放的 body。
        String payload = checkpoint == null ? null : JSON.toJson(checkpoint);
        try (var p = statement("""
                INSERT INTO segment(audience,origin,kind,body,matter,seen_epoch,seen_seq,checkpoint)
                SELECT ?,?,?,?,s.seq,?,?,? FROM segment s
                WHERE s.seq=? AND s.kind IN ('MATTER','REQUEST') AND s.audience=? AND ?=(SELECT max(id) FROM epoch)
                AND (s.kind='MATTER' OR s.origin=? OR s.seq<=? OR EXISTS(
                    SELECT 1 FROM segment f WHERE f.matter=s.seq AND f.kind='FAILED'))
                AND NOT EXISTS(SELECT 1 FROM segment r WHERE r.matter=s.seq AND r.kind='RECEIPT')""",
                audience, origin, kind.name(), body, lease, seen, payload, matter, audience, lease, origin, recoveredThrough)) {
            if (p.executeUpdate() == 1) return;
            // 只对同一来源、同一事项、同一回执正文的重放返回原结果。过期租约即使重放也没有写权限。
            try (var same = statement("""
                    SELECT COALESCE(checkpoint,'null') FROM segment WHERE kind='RECEIPT' AND kind=?
                    AND audience=? AND origin=? AND matter=? AND body=? AND ?=(SELECT max(id) FROM epoch)""",
                    kind.name(), audience, origin, matter, body, lease);
                 var old = same.executeQuery()) {
                if (!old.next() || !JsonParser.parseString(old.getString(1)).equals(JSON.toJsonTree(checkpoint)))
                    throw new IOException("Epoch 租约或本会话的未决事项与回执无效：" + matter);
            }
        } catch (SQLException error) {
            throw new IOException("Master 事项记录未能落盘", error);
        }
    }

    /** 当前 seq 是全局追加位置，即使末段未被候选选中也不会倒退。正常入站只查看水位，不扫描全日志。 */
    synchronized Head head() throws IOException {
        try (var p = db.createStatement(); var rows = p.executeQuery("""
                SELECT id,COALESCE((SELECT max(seq) FROM segment),0),bytes FROM epoch ORDER BY id DESC LIMIT 1""")) {
            rows.next();
            return new Head(rows.getLong(1), rows.getLong(2), rows.getLong(3));
        } catch (SQLException error) {
            throw new IOException("无法读取 Master 写租约", error);
        }
    }

    /** 在同一锁下冻结全局水位 S 和当时的未决状态。已归档的段不会再进入后续候选。 */
    synchronized Snapshot freeze(Head head) throws IOException {
        var labels = new ArrayList<Label>();
        try (var p = statement("""
                SELECT s.seq,s.kind,(s.kind<>'RECEIPT' AND r.seq IS NULL)
                  OR (m.carried>0 AND s.seq>(SELECT COALESCE(max(seen_seq),0) FROM segment
                      WHERE audience=s.audience AND seen_epoch>=m.carried)),
                  length(CAST(s.body AS BLOB))
                FROM member m JOIN segment s ON s.seq=m.seq
                LEFT JOIN segment r ON r.matter=COALESCE(s.matter,s.seq) AND r.kind='RECEIPT'
                WHERE m.epoch=? ORDER BY s.seq""", head.epoch()); var rows = p.executeQuery()) {
            while (rows.next()) labels.add(new Label(rows.getLong(1), Kind.valueOf(rows.getString(2)),
                    rows.getBoolean(3), rows.getLong(4)));
        } catch (SQLException error) {
            throw new IOException("无法冻结 Master 分段标签", error);
        }
        return new Snapshot(head.epoch(), head.seq(), List.copyOf(labels));
    }

    /** 只按当前受众投影。已登记的当前入站从历史中扣除，由调用方按原契约单独传给模型。 */
    synchronized String context(long epoch, String audience, long current) throws IOException {
        try (var p = statement("""
                SELECT s.seq,s.kind,s.body,s.matter,r.seq FROM member m JOIN segment s ON s.seq=m.seq
                LEFT JOIN segment r ON r.matter=s.seq AND r.kind='RECEIPT' AND NOT EXISTS(
                    SELECT 1 FROM epoch next WHERE next.id=m.epoch+1 AND next.through_seq<r.seq)
                WHERE m.epoch=? AND s.audience=? AND s.seq<>? ORDER BY s.seq""",
                epoch, audience, current); var rows = p.executeQuery()) {
            var text = new StringBuilder();
            while (rows.next()) {
                String label = switch (Kind.valueOf(rows.getString(2))) {
                    case MATTER, REQUEST -> "事项 " + rows.getLong(1) + (rows.getObject(5) == null ? " 仍待处理" : " 已结束");
                    case DECISION -> "事项 " + rows.getLong(4) + " 的裁决";
                    case FAILED -> "事项 " + rows.getLong(4) + " 的中断记录";
                    case RECEIPT -> "事项 " + rows.getLong(4) + " 的完成记录";
                };
                text.append(label).append("：").append(rows.getString(3)).append('\n');
            }
            return text.toString();
        } catch (SQLException error) {
            throw new IOException("无法重建 Master 上下文", error);
        }
    }

    /** 当前单步已落盘且写者暂停派发。在一个事务内保存保留段、顺序补齐 S 之后的全部增量并发布新租约。 */
    synchronized long switchEpoch(Snapshot snapshot, List<Long> retained) throws IOException {
        long through = head().seq();
        try {
            db.setAutoCommit(false);
            try (var create = statement("""
                    INSERT INTO epoch(id,frozen_seq,through_seq) SELECT ?,?,? WHERE (SELECT max(id) FROM epoch)=?""",
                         snapshot.epoch() + 1, snapshot.through(), through, snapshot.epoch());
                 var keep = statement("INSERT INTO member SELECT ?,seq,carried FROM member WHERE epoch=? AND seq=?");
                 var delta = statement("""
                    INSERT INTO member SELECT ?,seq,? FROM member WHERE epoch=? AND seq>? ORDER BY seq""",
                         snapshot.epoch() + 1, snapshot.epoch() + 1, snapshot.epoch(), snapshot.through())) {
                if (create.executeUpdate() != 1) throw new SQLException("冻结时的 Epoch 已失去写租约");
                for (long seq : retained) {
                    keep.setLong(1, snapshot.epoch() + 1);
                    keep.setLong(2, snapshot.epoch());
                    keep.setLong(3, seq);
                    keep.addBatch();
                }
                keep.executeBatch();
                delta.executeUpdate();
                try (var size = statement("""
                        UPDATE epoch SET bytes=(SELECT COALESCE(sum(length(CAST(s.body AS BLOB))),0)
                        FROM member m JOIN segment s ON s.seq=m.seq WHERE m.epoch=epoch.id) WHERE id=?""",
                        snapshot.epoch() + 1)) { size.executeUpdate(); }
                db.commit();
                return through;
            } catch (SQLException error) {
                db.rollback();
                throw error;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IOException("Master Epoch 交接未能提交", error);
        }
    }

    @Override public synchronized void close() throws IOException {
        try { db.close(); }
        catch (SQLException error) { throw new IOException("无法关闭 Master Epoch 日志", error); }
    }
}
