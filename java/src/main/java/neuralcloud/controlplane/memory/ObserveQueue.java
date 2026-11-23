package neuralcloud.controlplane.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** QQ 仅观察群的 P3 待办。启用水位、原文回执、在途批次与待投递审计共用 Harness 的 SQLite 日志。 */
public final class ObserveQueue {
    public static final int LIMIT = 20;
    static final long WAIT = Duration.ofMinutes(30).toMillis();
    private static final Gson JSON = new Gson();
    private final MemoryJournal db;
    private final String stream;
    private final Head head;

    @FunctionalInterface
    interface Head {
        long read() throws IOException;
    }

    public record Origin(
            long account, long room, long policy, String message, String runId, int eventId) {}

    public record Source(
            String event,
            String messageId,
            String runId,
            int eventId,
            String recordId,
            long revision,
            String stream,
            long sequence,
            long confirmedAt) {}

    public record Batch(
            String id,
            long account,
            long room,
            long policy,
            String state,
            long retryAt,
            JsonObject candidate) {
        public String chain() {
            return "qq-group-" + account + "-" + room;
        }
    }

    public record Status(
            int pending,
            String state,
            String lastCompletedAt,
            String lastResult,
            String lastBatchId,
            String currentBatchId) {}

    public record Audit(
            long key, String chain, String batch, String node, String type, JsonObject data) {}

    ObserveQueue(MemoryJournal db, String stream, Head head) {
        this.db = db;
        this.stream = stream;
        this.head = head;
    }

    /** 原文在发出前登记身份；只有确认 DURABLE 后才有 confirmed/seq，旧版本的 commands 无需迁移。 */
    static void createTables(Statement sql) throws SQLException {
        sql.execute(
                """
                CREATE TABLE IF NOT EXISTS observe_epochs(account INTEGER,room INTEGER,policy INTEGER,
                stream TEXT NOT NULL,start_seq INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(account,room,policy))""");
        sql.execute(
                """
                CREATE TABLE IF NOT EXISTS observe_sources(event TEXT PRIMARY KEY,account INTEGER NOT NULL,
                room INTEGER NOT NULL,policy INTEGER NOT NULL,message TEXT NOT NULL,run_id TEXT NOT NULL,
                event_id INTEGER NOT NULL,stream TEXT NOT NULL,confirmed INTEGER,record_id TEXT,revision INTEGER,
                seq INTEGER,batch TEXT,UNIQUE(account,room,message))""");
        sql.execute(
                "CREATE INDEX IF NOT EXISTS observe_pending ON observe_sources(account,room,policy,batch,seq)");
        sql.execute("CREATE INDEX IF NOT EXISTS observe_members ON observe_sources(batch,seq)");
        sql.execute(
                """
                CREATE TABLE IF NOT EXISTS observe_batches(id TEXT PRIMARY KEY,account INTEGER,room INTEGER,policy INTEGER,
                state TEXT NOT NULL,oldest INTEGER NOT NULL,reason TEXT NOT NULL,retry_at INTEGER NOT NULL DEFAULT 0,
                candidate TEXT,finished INTEGER,result TEXT)""");
        sql.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS observe_inflight ON observe_batches(account,room)
                WHERE state IN ('QUEUED','RUNNING','SENDING')""");
        sql.execute(
                """
                CREATE TABLE IF NOT EXISTS observe_audit(id INTEGER PRIMARY KEY,batch TEXT NOT NULL,node TEXT NOT NULL,
                type TEXT NOT NULL,data TEXT NOT NULL,published INTEGER NOT NULL DEFAULT 0)""");
        sql.execute(
                "CREATE INDEX IF NOT EXISTS observe_unpublished ON observe_audit(published,id)");
    }

    /** 发布策略文件前准备不可变启用水位。相同版本重启沿用原水位，换流须显式处理。 */
    public void prepareEpochs(long account, Map<Long, Long> enabled) throws IOException {
        var missing =
                db.transaction(
                        () -> {
                            try (var p =
                                            db.statement(
                                                    """
                    SELECT 1 FROM observe_batches b JOIN observe_epochs e USING(account,room,policy)
                    WHERE b.account=? AND b.state='SENDING' AND e.stream<>? LIMIT 1""",
                                                    account,
                                                    stream);
                                    var r = p.executeQuery()) {
                                if (r.next()) throw new IOException("未决印象候选属于另一条 Memory 流");
                            }
                            var result = new ArrayList<Long>();
                            for (var entry : enabled.entrySet()) {
                                try (var p =
                                                db.statement(
                                                        "SELECT stream FROM observe_epochs WHERE account=? AND room=? AND policy=?",
                                                        account,
                                                        entry.getKey(),
                                                        entry.getValue());
                                        var r = p.executeQuery()) {
                                    if (!r.next()) result.add(entry.getKey());
                                    else if (!stream.equals(r.getString(1)))
                                        throw new IOException("观察群启用水位属于另一条 Memory 流");
                                }
                            }
                            return result;
                        });
        if (missing.isEmpty()) return;
        long mark = head.read();
        db.transaction(
                () -> {
                    for (long room : missing)
                        db.exec(
                                """
                    INSERT INTO observe_epochs(account,room,policy,stream,start_seq) VALUES(?,?,?,?,?)""",
                                account,
                                room,
                                enabled.get(room),
                                stream,
                                mark);
                    return null;
                });
    }

    /** 策略文件是生效版本的依据。已发出的命令保留到按原键对账，未发出的旧批次就地取消。 */
    public void applyEpochs(long account, Map<Long, Long> enabled) throws IOException {
        db.transaction(
                () -> {
                    db.exec(
                            "UPDATE observe_epochs SET active=0 WHERE account=? AND active=1",
                            account);
                    for (var entry : enabled.entrySet())
                        db.exec(
                                "UPDATE observe_epochs SET active=1 WHERE account=? AND room=? AND policy=?",
                                account,
                                entry.getKey(),
                                entry.getValue());
                    try (var p =
                                    db.statement(
                                            """
                    SELECT id FROM observe_batches b WHERE account=? AND state IN ('QUEUED','RUNNING')
                    AND NOT EXISTS(SELECT 1 FROM observe_epochs e WHERE e.account=b.account AND e.room=b.room
                    AND e.policy=b.policy AND e.active=1)""",
                                            account);
                            var r = p.executeQuery()) {
                        var ids = new ArrayList<String>();
                        while (r.next()) ids.add(r.getString(1));
                        for (var id : ids) cancel(id);
                    }
                    return null;
                });
        wake();
    }

    private void cancel(String id) throws SQLException {
        db.exec("UPDATE observe_batches SET state='CANCELLED' WHERE id=?", id);
        note(id, "orchestrator", "observe_cancelled", Map.of("reason", "群策略版本已结束"));
    }

    private static final String ELIGIBLE =
            """
            FROM observe_sources s JOIN observe_epochs e USING(account,room,policy,stream)
            WHERE e.active=1 AND s.seq>e.start_seq
            """;

    /** 数量和首条到期共用一个事务与唯一索引。正在运行时调度线程也能为其他群入队。 */
    public long enqueueDue(long account) throws IOException {
        return db.transaction(
                () -> {
                    long next = Long.MAX_VALUE, now = now();
                    try (var p =
                                    db.statement(
                                            "SELECT s.room,s.policy,count(*),min(s.confirmed) "
                                                    + ELIGIBLE
                                                    + """
                    AND s.account=? AND s.batch IS NULL AND NOT EXISTS(SELECT 1 FROM observe_batches b
                    WHERE b.account=s.account AND b.room=s.room AND b.state IN ('QUEUED','RUNNING','SENDING'))
                    GROUP BY s.room,s.policy ORDER BY min(s.confirmed),s.room""",
                                            account);
                            var r = p.executeQuery()) {
                        while (r.next()) {
                            int count = r.getInt(3);
                            long oldest = r.getLong(4), due = oldest + WAIT;
                            if (count < LIMIT && now < due) {
                                next = Math.min(next, due);
                                continue;
                            }
                            String id = UUID.randomUUID().toString(),
                                    reason = count >= LIMIT ? "COUNT" : "AGE";
                            db.exec(
                                    """
                            INSERT INTO observe_batches(id,account,room,policy,state,oldest,reason)
                            VALUES(?,?,?,?,'QUEUED',?,?)""",
                                    id,
                                    account,
                                    r.getLong(1),
                                    r.getLong(2),
                                    oldest,
                                    reason);
                            try (var first =
                                            db.statement(
                                                    "SELECT s.run_id,s.event_id "
                                                            + ELIGIBLE
                                                            + " AND s.account=? AND s.room=? AND s.policy=? AND s.batch IS NULL ORDER BY s.seq LIMIT 1",
                                                    account,
                                                    r.getLong(1),
                                                    r.getLong(2));
                                    var source = first.executeQuery()) {
                                source.next();
                                note(
                                        id,
                                        "orchestrator",
                                        "observe_queued",
                                        Map.of(
                                                "priority",
                                                "P3",
                                                "reason",
                                                reason,
                                                "firstConfirmedAt",
                                                Instant.ofEpochMilli(oldest).toString(),
                                                "count",
                                                count,
                                                "sourceRunId",
                                                source.getString(1),
                                                "sourceEventId",
                                                source.getInt(2)));
                            }
                        }
                    }
                    return next;
                });
    }

    /** 先等最久的群先获得机会；失败退避期间仍可领取其他群。 */
    public List<Batch> outstanding(long account) throws IOException {
        return db.transaction(
                () -> {
                    var result = new ArrayList<Batch>();
                    try (var p =
                                    db.statement(
                                            """
                    SELECT id,room,policy,state,retry_at,candidate FROM observe_batches WHERE account=?
                    AND state IN ('QUEUED','RUNNING','SENDING') ORDER BY oldest,rowid""",
                                            account);
                            var r = p.executeQuery()) {
                        while (r.next())
                            result.add(
                                    new Batch(
                                            r.getString(1),
                                            account,
                                            r.getLong(2),
                                            r.getLong(3),
                                            r.getString(4),
                                            r.getLong(5),
                                            r.getString(6) == null
                                                    ? null
                                                    : JsonParser.parseString(r.getString(6))
                                                            .getAsJsonObject()));
                    }
                    return result;
                });
    }

    /** 初次领取只看本版本最早的二十条；重启重读原批成员，之后抵达的消息保留各自起点。 */
    public List<Source> sources(Batch b) throws IOException {
        return db.transaction(
                () -> {
                    String columns =
                            "SELECT s.event,s.message,s.run_id,s.event_id,s.record_id,s.revision,s.stream,s.seq,s.confirmed ";
                    boolean queued = b.state().equals("QUEUED");
                    String query =
                            queued
                                    ? ELIGIBLE
                                            + " AND s.account=? AND s.room=? AND s.policy=? AND s.batch IS NULL"
                                    : "FROM observe_sources s WHERE s.batch=?";
                    var result = new ArrayList<Source>();
                    try (var p =
                                    db.statement(
                                            columns
                                                    + query
                                                    + " ORDER BY s.seq,s.event LIMIT "
                                                    + LIMIT,
                                            queued
                                                    ? new Object[] {
                                                        b.account(), b.room(), b.policy()
                                                    }
                                                    : new Object[] {b.id()});
                            var r = p.executeQuery()) {
                        while (r.next())
                            result.add(
                                    new Source(
                                            r.getString(1),
                                            r.getString(2),
                                            r.getString(3),
                                            r.getInt(4),
                                            r.getString(5),
                                            r.getLong(6),
                                            r.getString(7),
                                            r.getLong(8),
                                            r.getLong(9)));
                    }
                    return List.copyOf(result);
                });
    }

    /** 输入预算内的完整原文前缀在模型调用前固定。修订增大已固定批次时重新排队，原等待起点仍在原文上。 */
    public void claim(Batch b, List<Source> sources) throws IOException {
        db.transaction(
                () -> {
                    for (var s : sources)
                        db.exec(
                                "UPDATE observe_sources SET batch=? WHERE event=? AND batch IS NULL",
                                b.id(),
                                s.event());
                    db.exec("UPDATE observe_batches SET state='RUNNING' WHERE id=?", b.id());
                    note(b.id(), "orchestrator", "observe_claimed", Map.of("sources", sources));
                    return null;
                });
        wake();
    }

    public void requeue(Batch b) throws IOException {
        db.transaction(
                () -> {
                    db.exec("UPDATE observe_sources SET batch=NULL WHERE batch=?", b.id());
                    db.exec("UPDATE observe_batches SET state='CANCELLED' WHERE id=?", b.id());
                    note(
                            b.id(),
                            "orchestrator",
                            "observe_cancelled",
                            Map.of("reason", "修订后整批原文超出输入预算，重新分批"));
                    return null;
                });
        wake();
    }

    /** 模型提交、发出前的完整不可变命令以及完成结论分别与状态在同一事务落盘。 */
    public void evidence(
            Batch b, String inputId, ObserveEvidence.Packet packet, List<String> comparison)
            throws IOException {
        db.transaction(
                () -> {
                    note(
                            b.id(),
                            "orchestrator",
                            "observe_evidence",
                            Map.of(
                                    "inputId",
                                    inputId,
                                    "originals",
                                    packet.originals(),
                                    "comparison",
                                    comparison));
                    return null;
                });
    }

    public void submitted(Batch b, Object submission) throws IOException {
        db.transaction(
                () -> {
                    note(b.id(), "master", "observe_submission", submission);
                    return null;
                });
    }

    public void sending(Batch b, JsonObject candidate) throws IOException {
        db.transaction(
                () -> {
                    db.exec(
                            "UPDATE observe_batches SET state='SENDING',candidate=? WHERE id=?",
                            candidate.toString(),
                            b.id());
                    note(b.id(), "router", "observe_candidate", candidate);
                    return null;
                });
    }

    /** SOURCE_CHANGED 已明确拒绝旧命令，允许重新取证并分配新键。 */
    public void refused(Batch b, String reason) throws IOException {
        db.transaction(
                () -> {
                    note(
                            b.id(),
                            "memory",
                            "observe_refused",
                            Map.of(
                                    "command",
                                    b.candidate().get("event_id").getAsString(),
                                    "reason",
                                    reason));
                    db.exec(
                            "UPDATE observe_batches SET state='RUNNING',candidate=NULL WHERE id=?",
                            b.id());
                    return null;
                });
        wake();
    }

    public void cancelUncommitted(Batch b) throws IOException {
        db.transaction(
                () -> {
                    cancel(b.id());
                    return null;
                });
        wake();
    }

    public void complete(Batch b, JsonObject receipt) throws IOException {
        String result = receipt == null ? "NO_IMPRESSION" : "IMPRESSION";
        db.transaction(
                () -> {
                    db.exec(
                            "UPDATE observe_batches SET state='DONE',result=?,finished=? WHERE id=?",
                            result,
                            now(),
                            b.id());
                    note(
                            b.id(),
                            receipt == null ? "orchestrator" : "memory",
                            "observe_completed",
                            receipt == null
                                    ? Map.of("result", result)
                                    : Map.of(
                                            "result",
                                            result,
                                            "command",
                                            b.candidate().get("event_id").getAsString(),
                                            "receipt",
                                            receipt));
                    return null;
                });
        wake();
    }

    public void retry(Batch b, IOException failure) throws IOException {
        db.transaction(
                () -> {
                    db.exec(
                            "UPDATE observe_batches SET retry_at=? WHERE id=?",
                            now() + Duration.ofSeconds(5).toMillis(),
                            b.id());
                    note(
                            b.id(),
                            "orchestrator",
                            "observe_retry",
                            Map.of("detail", failure.toString()));
                    return null;
                });
        wake();
    }

    /** 最近已确认的同群候选只提供记录指针，每次对照都要用当前受众重读其有效版本。 */
    public List<String> recentImpressions(long account, long room) throws IOException {
        return db.transaction(
                () -> {
                    var ids = new ArrayList<String>();
                    try (var p =
                                    db.statement(
                                            """
                    SELECT json_extract(a.data,'$.receipt.record_id') FROM observe_batches b
                    JOIN observe_epochs e USING(account,room,policy) JOIN observe_audit a ON a.batch=b.id
                    WHERE b.account=? AND b.room=? AND e.stream=? AND b.result='IMPRESSION'
                    AND a.type='observe_completed' ORDER BY b.finished DESC,b.rowid DESC LIMIT 3""",
                                            account,
                                            room,
                                            stream);
                            var r = p.executeQuery()) {
                        while (r.next()) ids.add(r.getString(1));
                    }
                    return List.copyOf(ids);
                });
    }

    /** 未完成计数包含已固定的批内原文。完成时间、结果及批号一起读取。 */
    public Status status(long account, long room, long policy, boolean connected)
            throws IOException {
        return db.transaction(
                () -> {
                    int count;
                    try (var p =
                                    db.statement(
                                            "SELECT count(*) "
                                                    + ELIGIBLE
                                                    + """
                    AND s.account=? AND s.room=? AND s.policy=? AND (s.batch IS NULL OR EXISTS
                    (SELECT 1 FROM observe_batches b WHERE b.id=s.batch AND b.state IN ('RUNNING','SENDING')))""",
                                            account,
                                            room,
                                            policy);
                            var r = p.executeQuery()) {
                        r.next();
                        count = r.getInt(1);
                    }
                    String state = count == 0 ? "IDLE" : "WAITING",
                            current = "",
                            last = "",
                            at = "",
                            result = "";
                    try (var p =
                                    db.statement(
                                            """
                    SELECT id,state,retry_at FROM observe_batches WHERE account=? AND room=? AND policy=?
                    AND state IN ('QUEUED','RUNNING','SENDING')""",
                                            account,
                                            room,
                                            policy);
                            var r = p.executeQuery()) {
                        if (r.next()) {
                            current = r.getString(1);
                            state = r.getLong(3) > now() ? "RETRY" : r.getString(2);
                        }
                    }
                    try (var p =
                                    db.statement(
                                            """
                    SELECT id,finished,result FROM observe_batches WHERE account=? AND room=?
                    AND state='DONE' ORDER BY finished DESC,rowid DESC LIMIT 1""",
                                            account,
                                            room);
                            var r = p.executeQuery()) {
                        if (r.next()) {
                            last = r.getString(1);
                            at = Instant.ofEpochMilli(r.getLong(2)).toString();
                            result = r.getString(3);
                        }
                    }
                    return new Status(
                            count, connected ? state : "DISCONNECTED", at, result, last, current);
                });
    }

    /** SQLite 审计 outbox 使“完成已落盘、JSONL 尚未写出”的窗口也能按批次恢复。 */
    private void note(String batch, String node, String type, Object data) throws SQLException {
        db.exec(
                "INSERT INTO observe_audit(batch,node,type,data) VALUES(?,?,?,?)",
                batch,
                node,
                type,
                JSON.toJson(data));
        wake();
    }

    public List<Audit> auditPending() throws IOException {
        return db.transaction(
                () -> {
                    var result = new ArrayList<Audit>();
                    try (var p =
                                    db.statement(
                                            """
                    SELECT a.id,b.account,b.room,b.id,b.policy,a.node,a.type,a.data FROM observe_audit a
                    JOIN observe_batches b ON b.id=a.batch WHERE a.published=0 ORDER BY a.id LIMIT 100""");
                            var r = p.executeQuery()) {
                        while (r.next()) {
                            var data = JsonParser.parseString(r.getString(8)).getAsJsonObject();
                            data.addProperty("batchId", r.getString(4));
                            data.addProperty("policyRevision", r.getLong(5));
                            data.addProperty("journalId", r.getLong(1));
                            result.add(
                                    new Audit(
                                            r.getLong(1),
                                            "qq-group-" + r.getLong(2) + "-" + r.getLong(3),
                                            r.getString(4),
                                            r.getString(6),
                                            r.getString(7),
                                            data));
                        }
                    }
                    return result;
                });
    }

    public void published(long key) throws IOException {
        db.transaction(
                () -> {
                    db.exec("UPDATE observe_audit SET published=1 WHERE id=?", key);
                    return null;
                });
    }

    public long now() {
        return db.now();
    }

    public void wake() {
        db.wake();
    }

    public long generation() {
        return db.generation();
    }

    public void await(long seen, long until) throws InterruptedException {
        db.await(seen, until);
    }
}
