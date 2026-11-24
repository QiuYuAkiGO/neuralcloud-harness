package neuralcloud.controlplane.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
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
    private static final int DIALOGUE_CHAR_BUDGET = 48_000;
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
    private record ResetAt(int position, String key) {}
    private final java.util.Map<String, ResetAt> chainResets = new HashMap<>();
    private final java.util.Map<String, ResetAt> sessionResets = new HashMap<>();
    private final BufferedWriter writer;
    final RollingChatContext rolling;
    private int eventId;

    private AuditLog(Path directory, String runId, List<Event> events, BufferedWriter writer) {
        this.directory = directory;
        this.runId = runId;
        this.events = events;
        this.writer = writer;
        this.rolling = new RollingChatContext(this);
        for (int i = 0; i < events.size(); i++) indexReset(events.get(i), i);
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
        indexReset(event, events.size() - 1);
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

    /** 重建和追加共用同一位置索引，普通模型调用的交付复核不必反复从头扫描 JSONL。 */
    private void indexReset(Event event, int position) {
        if (!event.type().equals("memory_context_reset")) return;
        var mark = new ResetAt(position, ChatHistory.key(event));
        var data = event.data().getAsJsonObject();
        if (data.has("sessionId")) sessionResets.put(data.get("sessionId").getAsString(), mark);
        else chainResets.put(event.chain(), mark);
    }

    /** 冻结和提交之间只比较最近一次重置的位置，普通新入站不会阻止提交。 */
    synchronized String latestReset(ConsoleServer.Trace.Scope scope) {
        var legacy = chainResets.get(scope.chain());
        var scoped = sessionResets.get(scope.sessionId());
        if (legacy == null) return scoped == null ? "" : scoped.key();
        return scoped == null || legacy.position() > scoped.position() ? legacy.key() : scoped.key();
    }

    // 回读原始渠道事件时同时匹配运行和会话，重启后 eventId 会重新编号。
    public synchronized Event event(ConsoleServer.Trace.Scope scope, int id) {
        return events.reversed().stream().filter(event -> event.eventId() == id
                && scope.runId().equals(event.runId()) && scope.chain().equals(event.chain())).findFirst().orElseThrow();
    }

    /**
     * 从审计链重建本轮请求（{@code request}）之前可用于续聊的纯文本多轮对话列表：
     * 1. 严格截断在当前运行的 {@code request} 之前，不把本轮正在处理的消息混入历史；
     * 2. 遇到 {@code memory_context_reset}（记忆证据发生修订/更正）时清空先前内存对话，强迫后续基于新证据重建上下文；
     * 3. 角色回复只回放经当前渠道明确确认写出的条目（{@code console_response_written} 或 {@code qq_delivery}），
     *    且排除带有 {@code source_refs} 的跨会话转授权回复（防止历史渠道回执绕过撤权永久重放）；
     * 4. 最后按 {@value #DIALOGUE_CHAR_BUDGET} 字符上限从末尾保留最近若干轮。
     */
    public synchronized List<DialogueMessage> dialogueBefore(String channel, String chain, int request) {
        var scope = new ConsoleServer.Trace.Scope(channel, chain, runId);
        var dialogue = new ArrayList<DialogueMessage>();
        var byRun = new HashMap<String, HashMap<Integer, Event>>();
        boolean qq = "openqq".equals(channel);
        for (Event event : events) {
            if (!chain.equals(event.chain())) {
                continue;
            }
            if (runId.equals(event.runId()) && event.eventId() >= request) {
                break;
            }
            if (ChatHistory.resetFor(event, scope)) dialogue.clear();
            var causalEvents = byRun.computeIfAbsent(event.runId(), ignored -> new HashMap<>());
            causalEvents.put(event.eventId(), event);
            var source = ChatHistory.visible(event, causalEvents, qq);
            // OFF 没有版本复核步骤，先前滚动档位中依赖可变证据的派生答复不能绕过撤权。
            if (source != null && source != event && event.data().getAsJsonObject().has("history_refs")
                    && !event.data().getAsJsonObject().getAsJsonArray("history_refs").isEmpty()) continue;
            if (source != null) dialogue.add(new DialogueMessage(source == event ? Speaker.USER : Speaker.MISTY,
                    source.data().getAsJsonObject().get("text").getAsString()));
        }
        int remaining = DIALOGUE_CHAR_BUDGET;
        int start = dialogue.size();
        while (start > 0 && dialogue.get(start - 1).text().length() <= remaining) {
            remaining -= dialogue.get(--start).text().length();
        }
        return List.copyOf(dialogue.subList(start, dialogue.size()));
    }

    @Override
    public synchronized void close() throws IOException {
        try (rolling) { writer.close(); }
    }
}
