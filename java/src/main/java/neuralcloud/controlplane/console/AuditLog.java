package neuralcloud.controlplane.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import neuralcloud.controlplane.memory.MemoryCall.EvidenceRef;
import neuralcloud.controlplane.worker.DialogueMessage;
import neuralcloud.controlplane.worker.DialogueMessage.Speaker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 控制台与全链路事件的 JSONL 审计日志引擎。
 * 持久化规则：
 * 1. 每次进程启动在指定目录下创建一个以 UTC 时间戳 + 随机 {@code runId} 命名的新 JSONL 文件，
 *    启动时按文件名排序把目录中全部历史文件读回内存，实现跨重启审计回溯与续聊；
 * 2. {@code causedBy} 仅在同一个 {@code runId} 内引用前置 {@code eventId}，{@code chain} 把同一会话或同一进程的探测串成一条链；
 * 3. 每次写入均即时 {@code flush()}，所有写与查询方法在实例级 {@code synchronized} 锁下保证顺序一致。
 */
public final class AuditLog implements AutoCloseable {
    /** 构造多轮续聊前缀对话时保留的最大历史正文总字符预算（从最近轮次向前裁剪）。 */
    public static final int DIALOGUE_CHAR_BUDGET = 48_000;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** 单条审计事件的不可变结构（schemaVersion 固定为 1）。 */
    public record Event(int schemaVersion, String runId, int eventId, Integer causedBy, String chain,
                        String node, String recordedAt, String type, JsonElement data) {
    }

    /** 审计链摘要视图，供控制台侧边栏展示链名、涉及节点列表、起止时间与事件总数。 */
    public record Chain(String chain, List<String> nodes, String firstAt, String lastAt, int events) {
    }

    private final Path directory;

    public Path directory() {
        return directory;
    }

    private final String runId;
    private final List<Event> events;
    private final BufferedWriter writer;
    private int eventId;

    private AuditLog(Path directory, String runId, List<Event> events, BufferedWriter writer) {
        this.directory = directory;
        this.runId = runId;
        this.events = events;
        this.writer = writer;
    }

    /** 打开审计目录：加载并严格校验全部历史 {@code .jsonl} 文件，再为本次进程运行创建新的独占追加文件。 */
    public static AuditLog open(Path directory) throws IOException {
        Files.createDirectories(directory);
        var events = new ArrayList<Event>();
        List<Path> files;
        try (var listing = Files.list(directory)) {
            files = listing.filter(path -> path.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        for (Path file : files) {
            var lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                events.add(parse(file, index + 1, lines.get(index)));
            }
        }
        // 文件名以 UTC 启动时间开头，按名称排序即按进程启动先后回放。
        String runId = UUID.randomUUID().toString();
        Path file = directory.resolve(FILE_TIME.format(Instant.now()) + "-" + runId + ".jsonl");
        return new AuditLog(
                directory,
                runId,
                events,
                Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    /** 严格反序列化单行 JSONL 审计记录并校验必需字段与版本号，防止损坏日志静默混入回放。 */
    private static Event parse(Path file, int line, String text) throws IOException {
        Event event;
        try {
            event = JSON.fromJson(text, Event.class);
        } catch (JsonParseException error) {
            throw new IOException(file + " 第 " + line + " 行不符合审计事件契约", error);
        }
        if (event == null || event.schemaVersion() != 1 || event.runId() == null || event.eventId() <= 0
                || event.chain() == null || event.node() == null || event.recordedAt() == null
                || event.type() == null || event.data() == null) {
            throw new IOException(file + " 第 " + line + " 行不符合审计事件契约");
        }
        return event;
    }

    public String runId() {
        return runId;
    }

    /**
     * 原子递增当前运行的 {@code eventId}、追加写入 JSONL 并立即刷盘，随后放入内存索引。
     * 每写一行即刷新，确保进程意外中止时已发生的步骤不丢失。
     */
    public synchronized int write(String chain, Integer causedBy, String node, String type, Object data)
            throws IOException {
        var event = new Event(1, runId, ++eventId, causedBy, chain, node, Instant.now().toString(), type,
                JSON.toJsonTree(data));
        writer.write(JSON.toJson(event));
        writer.newLine();
        writer.flush();
        events.add(event);
        return event.eventId();
    }

    /** 批次审计跨进程按 SQLite 序号幂等投递；同次运行中的步骤继续用 causedBy 连接。 */
    public synchronized void observation(neuralcloud.controlplane.memory.ObserveQueue.Audit row)
            throws IOException {
        Integer parent = null;
        for (var event : events(row.chain()).reversed()) {
            if (!event.type().startsWith("observe_")) continue;
            var data = event.data().getAsJsonObject();
            if (!row.batch().equals(data.get("batchId").getAsString())) continue;
            if (data.get("journalId").getAsLong() == row.key()) return;
            if (parent == null && runId.equals(event.runId())) parent = event.eventId();
        }
        if (parent == null
                && row.data().has("sourceRunId")
                && runId.equals(row.data().get("sourceRunId").getAsString()))
            parent = row.data().get("sourceEventId").getAsInt();
        write(row.chain(), parent, row.node(), row.type(), row.data());
    }

    /** 按最近活跃时间倒序汇总全部审计链摘要（最近有新事件的链排在最前）。 */
    public synchronized List<Chain> chains() {
        var grouped = new LinkedHashMap<String, List<Event>>();
        for (Event event : events.reversed()) {
            grouped.computeIfAbsent(event.chain(), key -> new ArrayList<>()).add(event);
        }
        return grouped.values().stream().map(newestFirst -> {
            var nodes = new LinkedHashSet<String>();
            newestFirst.reversed().forEach(event -> nodes.add(event.node()));
            return new Chain(newestFirst.getFirst().chain(), List.copyOf(nodes), newestFirst.getLast().recordedAt(),
                    newestFirst.getFirst().recordedAt(), newestFirst.size());
        }).toList();
    }

    public synchronized List<Event> events(String chain) {
        return events.stream().filter(event -> event.chain().equals(chain)).toList();
    }

    // 回读原始渠道事件时同时匹配运行和会话，重启后 eventId 会重新编号。
    public synchronized Event event(ConsoleServer.Trace.Scope scope, int id) {
        return events.reversed().stream().filter(event -> event.eventId() == id
                && scope.runId().equals(event.runId()) && scope.chain().equals(event.chain())).findFirst().orElseThrow();
    }

    /** 保存原始说话者和写入幂等键，直到读取方在实际受众下核验该条记录。 */
    public record HistoryEntry(String eventKey, DialogueMessage message, boolean writeRequested,
                               String recordId, long revision, List<EvidenceRef> directSources) {
        public boolean sourced() { return !directSources.isEmpty(); }

        /** 审计回执和幂等对账走同一入口，保留渠道见证的说话者及直接来源。 */
        public HistoryEntry withReceipt(String id, long version) {
            return new HistoryEntry(eventKey, message, true, id, version, directSources);
        }
    }

    /** 原渠道投影仍供无 v2 的入口使用。v2 从同一组渠道事件出发，逐条核验后再做预算裁剪。 */
    public List<DialogueMessage> dialogueBefore(String channel, String chain, int request) {
        var rows = historyBefore(channel, chain, request, false).stream().map(HistoryEntry::message).toList();
        int remaining = DIALOGUE_CHAR_BUDGET, start = rows.size();
        while (start > 0 && rows.get(start - 1).text().length() <= remaining)
            remaining -= rows.get(--start).text().length();
        return rows.subList(start, rows.size());
    }

    /** 以原运行和来源事件关联写入意图及持久回执。QQ 以实际送达为来源，网页以响应写出为来源。 */
    public synchronized List<HistoryEntry> historyBefore(String channel, String chain, int request, boolean verifyVersions) {
        var rows = new LinkedHashMap<String, HistoryEntry>();
        var byRun = new HashMap<String, HashMap<Integer, Event>>();
        boolean qq = "openqq".equals(channel);
        for (Event event : events) {
            if (!chain.equals(event.chain())) continue;
            if (runId.equals(event.runId()) && event.eventId() >= request) break;
            if (!verifyVersions && "memory_context_reset".equals(event.type())) rows.clear();
            var causal = byRun.computeIfAbsent(event.runId(), ignored -> new HashMap<>());
            causal.put(event.eventId(), event);
            var parent = causal.get(event.causedBy());
            String key = event.runId() + ":" + event.eventId();
            if (verifyVersions && ("router_memory_write".equals(event.type()) || "memory_durable".equals(event.type()))) {
                boolean receipt = "memory_durable".equals(event.type());
                var writing = receipt ? parent : event;
                if (writing != null && "router_memory_write".equals(writing.type())
                        && writing.data().getAsJsonObject().has("sourceEventId")) {
                    String source = event.runId() + ":" + writing.data().getAsJsonObject().get("sourceEventId").getAsInt();
                    var row = rows.get(source);
                    if (row != null) {
                        var data = event.data().getAsJsonObject();
                        rows.put(source, row.withReceipt(
                                receipt ? data.get("recordId").getAsString() : null,
                                receipt && data.has("revision") ? data.get("revision").getAsLong() : 0));
                    }
                }
                continue;
            }
            if (qq ? "qq_message".equals(event.type()) && "openqq".equals(event.node())
                    : "operator_message".equals(event.type())
                            && ("chat-worker".equals(event.node()) || "conversation".equals(event.node()))) {
                rows.put(key, new HistoryEntry(key, new DialogueMessage(Speaker.USER,
                        event.data().getAsJsonObject().get("text").getAsString()), false, null, 0, List.of()));
                continue;
            }
            if (qq && "qq_delivery".equals(event.type()) && "openqq".equals(event.node()) && parent != null
                    && "qq_send_requested".equals(parent.type()) && "openqq".equals(parent.node())) {
                parent = causal.get(parent.causedBy());
            } else if (qq || !"console_response_written".equals(event.type()) || !"console".equals(event.node())) {
                continue;
            }
            if (parent != null && "chat_worker_reply".equals(parent.type()) && "chat-worker".equals(parent.node())) {
                var data = event.data().getAsJsonObject();
                List<EvidenceRef> sources = List.of();
                if (data.has("source_refs")) {
                    // 只有能按本轮受众复核的读取方可以接收此候选；已送达本身不能授予引用权。
                    if (!verifyVersions) continue;
                    sources = data.getAsJsonArray("source_refs").asList().stream()
                            .map(ref -> JSON.fromJson(ref, EvidenceRef.class)).toList();
                    if (sources.isEmpty()) throw new IllegalStateException("送达事件的 source_refs 须含来源版本");
                }
                rows.put(key, new HistoryEntry(key, new DialogueMessage(Speaker.MISTY,
                        parent.data().getAsJsonObject().get("text").getAsString()), !sources.isEmpty(), null, 0, sources));
            }
        }
        return List.copyOf(rows.values());
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
