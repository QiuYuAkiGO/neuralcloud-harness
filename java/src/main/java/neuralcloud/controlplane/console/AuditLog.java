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
 * 控制台审计链。每个进程写一份新的 JSONL，启动时按文件名顺序读回目录中的历史文件。
 * causedBy 只引用同一 runId 内的 eventId；chain 把同一会话或同一进程的探测串成一条链。
 */
public final class AuditLog implements AutoCloseable {
    private static final int DIALOGUE_CHAR_BUDGET = 48_000;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);

    public record Event(int schemaVersion, String runId, int eventId, Integer causedBy, String chain,
                        String node, String recordedAt, String type, JsonElement data) {
    }

    public record Chain(String chain, List<String> nodes, String firstAt, String lastAt, int events) {
    }

    private final String runId;
    private final List<Event> events;
    private final BufferedWriter writer;
    private int eventId;

    private AuditLog(String runId, List<Event> events, BufferedWriter writer) {
        this.runId = runId;
        this.events = events;
        this.writer = writer;
    }

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
        return new AuditLog(runId, events,
                Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

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

    // 每写一行就刷新，进程异常退出时保留已经发生的步骤。
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

    // 最近有新事件的链排在前面。
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
            if (parent != null && "chat_worker_reply".equals(parent.type()) && "chat-worker".equals(parent.node())) {
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
