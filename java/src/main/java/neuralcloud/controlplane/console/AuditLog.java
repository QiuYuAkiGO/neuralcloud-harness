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

    /**
     * 从审计链重建本轮请求（{@code request}）之前可用于续聊的纯文本多轮对话列表：
     * 1. 严格截断在当前运行的 {@code request} 之前，不把本轮正在处理的消息混入历史；
     * 2. 遇到 {@code memory_context_reset}（记忆证据发生修订/更正）时清空先前内存对话，强迫后续基于新证据重建上下文；
     * 3. 角色回复只回放经当前渠道明确确认写出的条目（{@code console_response_written} 或 {@code qq_delivery}），
     *    且排除带有 {@code source_refs} 的跨会话转授权回复（防止历史渠道回执绕过撤权永久重放）；
     * 4. 最后按 {@value #DIALOGUE_CHAR_BUDGET} 字符上限从末尾保留最近若干轮。
     */
    public synchronized List<DialogueMessage> dialogueBefore(String channel, String chain, int request) {
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
            if ("memory_context_reset".equals(event.type())) dialogue.clear();
            var causalEvents = byRun.computeIfAbsent(event.runId(), ignored -> new HashMap<>());
            causalEvents.put(event.eventId(), event);
            if (qq ? "qq_message".equals(event.type()) && "openqq".equals(event.node())
                    : "operator_message".equals(event.type()) && "chat-worker".equals(event.node())) {
                dialogue.add(new DialogueMessage(Speaker.USER, event.data().getAsJsonObject().get("text").getAsString()));
                continue;
            }
            // 只回放经当前渠道确认写出的角色正文，消息 ID 只在同一个 runId 内回溯。
            var parent = causalEvents.get(event.causedBy());
            if (qq && "qq_delivery".equals(event.type()) && "openqq".equals(event.node()) && parent != null
                    && "qq_send_requested".equals(parent.type()) && "openqq".equals(parent.node())) {
                parent = causalEvents.get(parent.causedBy());
            } else if (qq || !"console_response_written".equals(event.type()) || !"console".equals(event.node())) {
                continue;
            }
            // 转授权回复只从 Memory 按当前受众重读；历史渠道回执不能永久授权重放正文。
            if (parent != null
                    && "chat_worker_reply".equals(parent.type())
                    && "chat-worker".equals(parent.node())
                    && !event.data().getAsJsonObject().has("source_refs")) {
                dialogue.add(new DialogueMessage(Speaker.MISTY, parent.data().getAsJsonObject().get("text").getAsString()));
            }
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
        writer.close();
    }
}
