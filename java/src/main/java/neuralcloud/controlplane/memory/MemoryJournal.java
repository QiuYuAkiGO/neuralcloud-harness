package neuralcloud.controlplane.memory;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Harness 自持的 Memory v2 本地 SQLite 持久日志（WAL + FULL 同步模式）。
 * 核心职责：
 * 1. {@code commands} 表（写前队列 / Outbox）：先将待写入记忆服务的事件落盘，再发送 HTTP 请求；
 *    即使网络超时丢失响应或进程崩溃，重启后也能按 {@code event} 幂等对账补齐回执；
 * 2. {@code inputs} 与 {@code input_evidence} 表（不可变取证审计）：精确登记每次交给模型/Worker 的材料文本及其证据快照；
 * 3. {@code watches} 表（变更订阅游标）：为每条已交付给某角色/会话的证据保留独立的 {@code stream/seq} 水位，
 *    确保后续轮次能通过 {@code v2/changes} 捕获修订、撤回或过期通知，即使晚到达的输入也能各自补齐差异。
 * 所有读写方法均在实例级 {@code synchronized} 锁下串行访问单个 SQLite 连接。
 */
public final class MemoryJournal implements AutoCloseable {
    private final Connection db;

    /** 打开或初始化指定路径的 SQLite 日志库，开启 WAL 与 FULL 同步以防掉电丢回执。 */
    public MemoryJournal(Path path) throws IOException {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (var sql = db.createStatement()) {
                sql.execute("PRAGMA journal_mode=WAL");
                sql.execute("PRAGMA synchronous=FULL");
                // commands：待发送及已确认回执的写命令队列，主键 event 为全局幂等事件 ID。
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS commands(event TEXT PRIMARY KEY,body TEXT NOT"
                            + " NULL,receipt TEXT,stream TEXT NOT NULL)");
                // inputs：记录单次交付给模型的输入材料包及其状态（PREPARED -> DELIVERED）。
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS inputs(input_id TEXT PRIMARY KEY,worker"
                            + " TEXT,scope TEXT,status TEXT,material TEXT)");
                // input_evidence：不可变地保存某次输入所依赖的每条记忆证据快照，用于审计与重启复盘。
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS input_evidence(input_id TEXT,kind TEXT,id"
                            + " TEXT,stream TEXT,seq INTEGER,item TEXT,PRIMARY"
                            + " KEY(input_id,kind,id))");
                // watches：活跃变更监视项，保留每个 (worker, scope, kind, id, input_id) 的独立快照水位。
                sql.execute(
                        "CREATE TABLE IF NOT EXISTS watches(worker TEXT,scope TEXT,kind TEXT,id"
                            + " TEXT,stream TEXT,seq INTEGER,item TEXT,input_id TEXT,PRIMARY"
                            + " KEY(worker,scope,kind,id,input_id))");
            }
        } catch (SQLException e) {
            throw new IOException("无法打开 Harness 记忆日志", e);
        }
    }

    /** 单条证据读取快照及监视记录：关联消费角色、会话范围、查询类别、记录 ID、记忆流、读取序号与完整 JSON 项。 */
    record Read(
            String worker,
            String scope,
            String kind,
            String id,
            String stream,
            long seq,
            JsonObject item,
            String input) {}

    /** 创建并按位置绑定参数的预编译 SQL 语句。 */
    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        var p = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
        return p;
    }

    /** 执行单条非查询 SQL 更新语句并自动关闭语句句柄。 */
    private void exec(String sql, Object... args) throws SQLException {
        try (var p = statement(sql, args)) {
            p.executeUpdate();
        }
    }

    /**
     * 将写命令幂等入队到 {@code commands} 表。
     * 若相同 {@code event} 已存在，严格比对请求体和所属 {@code stream} 是否完全一致，防止误用同一事件 ID 提交不同载荷。
     */
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

    /**
     * 按写入顺序（{@code rowid}）列出尚未收到回执（{@code receipt IS NULL}）的全部待发送命令。
     * 若发现未决命令归属另一条记忆流（意味着远端记忆库发生重置或错配），立即报错阻断静默覆盖。
     */
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

    /** 将远端确认的持久回执（或明确拒绝结果）写回 {@code commands} 表，标记该命令已终结。 */
    synchronized void ack(String event, JsonObject receipt) throws IOException {
        try {
            exec("UPDATE commands SET receipt=? WHERE event=?", receipt.toString(), event);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    /**
     * 为 v2 的 {@code recent} 非分域模式查询当前会话在 {@code before} 之前已持久落盘的最近最多 6 条对话记录 ID，
     * 最终反转为时间正序返回供调用方回读。
     */
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

    /** 查询指定角色与会话下当前所有处于监视中的证据快照列表，供每轮开始前对账增量变更。 */
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

    /**
     * 在单事务内登记一份即将送入模型的材料包（状态置为 {@code PREPARED}）及其全部证据快照。
     * 参数 {@code watch} 控制是否同时写入 {@code watches} 订阅表：
     * - 常规召回和刷新设为 {@code true}，用于后续捕获修订/失效；
     * - 热目录卡片设为 {@code false}，因为热目录每轮都会重新构建，只需保留不可变的审计证据即可。
     */
    synchronized void prepare(
            String input, String worker, String scope, String material, List<Read> bindings,
            boolean watch)
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
                    // 热目录指针每轮都会重建，无需加入长期变更监视表，但 input_evidence 仍保留不可变审计记录。
                    if (!watch)
                        continue;
                    // 晚到达的输入单独保留自身的 read_seq，即使同一记录之前已被其他输入推进过水位，也不会漏检变更。
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

    /** 将已准备好的输入包状态更新为 {@code DELIVERED}，确认该批证据已实际交由模型使用。 */
    synchronized void delivered(String input) throws IOException {
        try {
            exec("UPDATE inputs SET status='DELIVERED' WHERE input_id=?", input);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    /**
     * 在单事务内提交本轮变更刷新结果：
     * 1. 将刷新输入包标记为 {@code DELIVERED}；
     * 2. 按单调水位条件（同一记忆流只向前推进、切换记忆流则整体更新）推进对应 {@code watches} 记录；
     * 3. 仅合并已经收敛到完全相同 {@code (kind, id, stream, seq, item)} 的冗余监视行，避免表无限膨胀，
     *    同时保持 {@code input_evidence} 历史审计记录原封不动。
     */
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
                // 仅合并已经同步到完全一致证据和水位线的订阅条目；上方的 input_evidence 保持不可变供审计与重启使用。
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

    /** 关闭底层 SQLite 连接并释放文件锁。 */
    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
