package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.router.Route;
import neuralcloud.controlplane.router.Router;
import neuralcloud.controlplane.worker.ChatWorker.ApiFormat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 手动合成 A/B 回放。两个渠道出口都是新建的本地审计，唯一外部连接是所选 OpenAI 兼容模型。 */
public final class ChatCompactionReplay {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String SHORT = "我在试一盏暖黄色的台灯，随便聊一句吧。";
    private static final String TRIGGER = "我已经把这些页面整理好了，你先回一句收到吧。";
    private static final String REFERENCE = "那两个候选我还拿不定主意。把地名并排写给我，再提醒我原本打算等什么。";
    private static final String ORIGINAL = "夏至的小旅行还没定。我想在青岚渡和白芦湾中选一处，等潮汐表出来再决定。先替我留着这两个候选。";
    private record Reference(String dataset, String source, String question, String first, String second, List<String> waitTerms) {}
    private static final Reference COAST = new Reference("synthetic-coastal-notebook-v1", ORIGINAL, REFERENCE,
            "青岚渡", "白芦湾", List.of("潮汐"));
    private static final Reference HOLDOUT = new Reference("synthetic-herbarium-holdout-v1",
            "秋天的写生活动还没选好场地。我在槐纹坞和棠釉埠之间犹豫，要等索道检修报告出来再定。两个地点都先留着。",
            "上次那两个备选地点，我们先把名字并排写下来吧。要等什么出来，我才能定？",
            "槐纹坞", "棠釉埠", List.of("索道", "检修", "报告"));
    private static final List<String> PHASES = List.of("short", "trigger", "reference");
    private static final String HELP = """
            ChatCompactionReplay [--holdout] <Chat Worker properties> <新的输出目录> [historyBudgetUnits] [重复次数]
            缺省预算 48000，每侧每次各执行三轮。模型和 Router 调用均使用所选服务和该配置指定的环境密钥。
            --holdout 从同一合成长历史的固定截点开始，每侧只问一次远距问题。预先登记数据见 holdout-case.json。
            report.json 给出延迟、供应商用量、压缩诊断和远距指代结果，每侧 wire.jsonl 保存请求与原始 usage。
            """;

    private enum Status { DELIVERED, FAILED, SKIPPED_DEPENDENCY, ARM_UNAVAILABLE }

    private record Turn(int sample, String side, String phase, Status status, String error, Long elapsedMs, String answer,
                        Boolean recallsBothPlaces, Boolean recallsTide, Boolean recallsWaitCondition, int attempts,
                        List<JsonObject> calls, List<JsonObject> diagnostics) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) { System.out.print(HELP); return; }
        boolean holdout = args.length > 0 && "--holdout".equals(args[0]);
        if (holdout) args = Arrays.copyOfRange(args, 1, args.length);
        var scenario = holdout ? HOLDOUT : COAST;
        var phases = holdout ? List.of("reference") : PHASES;
        if (args.length < 2 || args.length > 4) throw new IllegalArgumentException(HELP);
        var original = ConfigSource.file(Path.of(args[0]));
        var baseline = ChatWorkerConfig.load(original);
        if (baseline.apiFormat() != ApiFormat.OPENAI_CHAT) throw new IllegalArgumentException("回放配置须为 OPENAI_CHAT");
        int budget = args.length > 2 ? Integer.parseInt(args[2]) : 48_000;
        if (budget < 4_096 || budget > 48_000) throw new IllegalArgumentException("historyBudgetUnits 须在 4096 至 48000");
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        if (samples < 1) throw new IllegalArgumentException("重复次数须为正数");
        String key = baseline.apiKey();
        var directory = Files.createDirectory(Path.of(args[1]).toAbsolutePath());
        var fixedHistory = holdout ? holdoutHistory(directory) : List.<String>of();
        var profile = original.readProperties();
        var turns = new ArrayList<Turn>();
        var armIssues = new ArrayList<Map<String, Object>>();
        Exception failure = null;
        try {
            for (int sample = 1; sample <= samples; sample++) {
                // 供应商缓存可能跨请求复用，多次回放交替先运行的档位。
                var order = sample % 2 == 1 ? List.of("OFF", "ROLLING") : List.of("ROLLING", "OFF");
                for (String side : order) {
                    int firstTurn = turns.size();
                    try {
                        runSide(directory, profile, key, budget, sample, side, turns, scenario, fixedHistory);
                    } catch (IOException | IllegalArgumentException error) {
                        // 建库、读配置或收尾失败只影响这一侧。保留已返回的轮次，为其余预定样本记下缺口。
                        armIssues.add(Map.of("sample", sample, "side", side, "error", error.toString()));
                        var observed = turns.subList(firstTurn, turns.size()).stream().map(Turn::phase).toList();
                        for (String phase : phases) if (!observed.contains(phase))
                            turns.add(unrun(sample, side, phase, Status.ARM_UNAVAILABLE, error.toString()));
                    }
                }
            }
            if (!armIssues.isEmpty() || turns.stream().anyMatch(t -> t.status() != Status.DELIVERED))
                throw new IOException("回放存在失败或缺失的轮次，请查看 report.json 中的逐轮状态和配对缺口");
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            var report = new LinkedHashMap<String, Object>();
            report.put("dataset", scenario.dataset());
            report.put("model", baseline.model());
            report.put("budget", budget);
            report.put("requestedSamples", samples);
            report.put("completedTurns", turns.stream().filter(t -> t.status() == Status.DELIVERED).count());
            report.put("runFailure", failure == null ? null : failure.toString());
            report.put("armIssues", armIssues);
            if (!holdout) {
                report.put("shortQuestion", SHORT);
                report.put("triggerQuestion", TRIGGER);
            }
            report.put("referenceQuestion", scenario.question());
            report.put("referenceExpected", List.of(scenario.first(), scenario.second(), String.join("", scenario.waitTerms())));
            report.put("arms", Map.of("OFF", stats(turns, "OFF"), "ROLLING", stats(turns, "ROLLING")));
            var paired = new ArrayList<Long>();
            var gaps = new ArrayList<Map<String, Object>>();
            for (int sample = 1; sample <= samples; sample++) {
                for (String phase : phases) {
                    var off = observed(turns, sample, "OFF", phase);
                    var rolling = observed(turns, sample, "ROLLING", phase);
                    if (off.isPresent() && off.get().status() == Status.DELIVERED
                            && rolling.isPresent() && rolling.get().status() == Status.DELIVERED) {
                        if (rolling.get().attempts() > 0) paired.add(rolling.get().elapsedMs() - off.get().elapsedMs());
                    } else {
                        gaps.add(Map.of("sample", sample, "phase", phase,
                                "OFF", off.map(t -> t.status().name()).orElse("NOT_RUN"),
                                "ROLLING", rolling.map(t -> t.status().name()).orElse("NOT_RUN")));
                    }
                }
            }
            report.put("pairedTriggerAddedMs", percentiles(paired));
            report.put("pairGaps", gaps);
            report.put("turns", turns);
            var path = directory.resolve("report.json");
            Files.writeString(path, JSON.toJson(report));
            System.out.println(path);
        }
    }

    private static Optional<Turn> observed(List<Turn> turns, int sample, String side, String phase) {
        return turns.stream().filter(t -> t.sample() == sample && t.side().equals(side) && t.phase().equals(phase)).findFirst();
    }

    private static Turn unrun(int sample, String side, String phase, Status status, String error) {
        return new Turn(sample, side, phase, status, error, null, null, null, null, null, 0, List.of(), List.of());
    }

    /** 在第一次模型请求前写出首条线索、截点指纹和逐字评分约定。两侧使用同一不可变输入序列。 */
    private static List<String> holdoutHistory(Path directory) throws IOException {
        var messages = new ArrayList<String>();
        messages.add(HOLDOUT.source());
        int tailUnits = 0;
        for (int page = 1; tailUnits < 50_000; page++) {
            String record = "这是植物标本簿第" + page + "页。"
                    + "叶片已经压干，叶脉和边缘的形状也抄进了方格纸。纸带换成新的，编号逐个核过，这页已归档。".repeat(8);
            messages.add(record);
            tailUnits += record.length();
        }
        Files.writeString(directory.resolve("holdout-case.json"), JSON.toJson(Map.of(
                "reference", HOLDOUT, "messages", messages.size(), "historyUnits", HOLDOUT.source().length() + tailUnits,
                "tailUnitsAfterClue", tailUnits, "historySha256", Relay.sha256(JSON.toJson(messages).getBytes(StandardCharsets.UTF_8)),
                "scoring", "两处地名逐字命中，等待条件的各词须全部命中。参照答复全文复核。")));
        return List.copyOf(messages);
    }

    /** 档位只改变两个压缩属性。角色、模型、只读参与偏好及供应商传输配置来自同一份正式配置。 */
    private static void runSide(Path output, Properties original, String key, int budget, int sample, String side,
                                List<Turn> turns, Reference scenario, List<String> fixedHistory) throws IOException, InterruptedException {
        Path directory = Files.createDirectory(output.resolve("sample-" + sample + "-" + side));
        var profile = new Properties();
        profile.putAll(original);
        profile.remove("contextCompression");
        profile.remove("historyBudgetUnits");
        if ("ROLLING".equals(side)) {
            profile.setProperty("contextCompression", side);
            profile.setProperty("historyBudgetUnits", String.valueOf(budget));
        }
        Path properties = directory.resolve("chat-worker.properties");
        try (var writer = Files.newBufferedWriter(properties)) { profile.store(writer, "synthetic replay"); }
        var settings = ChatWorkerConfig.load(ConfigSource.file(properties));
        try (var upstream = settings.newClient();
             var relay = new Relay(settings, upstream, directory.resolve("wire.jsonl"), scenario);
             var loopback = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
             var audit = AuditLog.open(directory.resolve("audit"))) {
            var transport = new ChatCompactionFixture.LoopbackTransport(loopback, relay.endpoint());
            var chat = settings.create(transport, key, ConfigSource.bundled("chat-worker-misty-prompt.txt").readText());
            var routing = new Master.Settings(settings.endpoint(), settings.model(), settings.httpVersion(), settings.connectTimeout(),
                    settings.requestTimeout(), "测试", 8, 0, settings.enableThinking(), key, settings.thinkingFormat(), settings.proxy());
            var router = new Router<>(new Master(transport, routing), ConfigSource.bundled("router-role-prompt.txt").readText(), Route.class);
            var entry = ConsoleMain.conversation(chat, router, Optional.empty(), Optional.empty());
            if (!fixedHistory.isEmpty()) {
                for (String text : fixedHistory) audit.write("holdout", null, "chat-worker", "operator_message", Map.of("text", text));
                turn(sample, side, "reference", "holdout", scenario.question(), entry, audit, relay, turns);
                return;
            }
            turn(sample, side, "short", "short", SHORT, entry, audit, relay, turns);
            audit.write("coast", null, "chat-worker", "operator_message", Map.of("text", ORIGINAL));
            int nextPage = addPages(audit, 1, 3 * budget / 4 + 128);
            // 续聊使用本侧首个长轮的实际交付和快照，因此该轮失败时记录依赖缺口。
            if (!turn(sample, side, "trigger", "coast", TRIGGER, entry, audit, relay, turns)) {
                turns.add(unrun(sample, side, "reference", Status.SKIPPED_DEPENDENCY, "本侧 trigger 轮未交付"));
                return;
            }
            addPages(audit, nextPage, 3 * budget / 8 + 512);
            turn(sample, side, "reference", "coast", REFERENCE, entry, audit, relay, turns);
        }
    }

    /** 每条记事一次性入账为原始发言。两侧按序号生成同一批合成数据，短轮使用独立会话。 */
    private static int addPages(AuditLog audit, int page, int minimumUnits) throws IOException {
        var subjects = List.of("瓷杯", "布伞", "木凳", "钥匙扣", "画框", "竹篮", "书签", "陶壶");
        int added = 0;
        while (added < minimumUnits) {
            var entry = new StringBuilder("我把今天的旧物图册抄好了。\n");
            for (int row = 0; row < 6; row++, page++) {
                entry.append("第").append(page).append("页画的是").append(subjects.get((page - 1) % subjects.size()))
                        .append("。上午在窗边用铅笔画了轮廓，下午把影子和边缘补全，再抄到干净的纸上。页码和日期都核过，这页已经收好。\n");
            }
            String text = entry.toString();
            audit.write("coast", null, "chat-worker", "operator_message", Map.of("text", text));
            added += text.length();
        }
        return page;
    }

    /** 耗时从当前入站算到渠道确认。语义字段仅按原问所需的地名和等待事项做字面计数，并保留全文供评阅。 */
    private static boolean turn(int sample, String side, String phase, String chain, String question, ConsoleServer.Exchange entry,
                                AuditLog audit, Relay relay, List<Turn> turns) throws IOException, InterruptedException {
        int before = relay.calls.size();
        long start = System.nanoTime();
        int request = audit.write(chain, null, "chat-worker", "operator_message", Map.of("text", question));
        var trace = Trace.forChain(audit, "console", chain, request);
        String delivered = null;
        String failure = null;
        try {
            var response = entry.send(question, trace);
            int draft = trace.write(response.causedBy(), "chat-worker", response.type(), response.data());
            if (!"chat_worker_reply".equals(response.type()))
                throw new IOException("本轮未生成聊天答复，见 " + side + "/" + phase + " 的审计，返回 " + response.type());
            trace.write(draft, "console", "console_response_written", trace.delivery(Map.of()));
            delivered = (String) response.data().get("text");
        } catch (IOException | IllegalArgumentException error) {
            failure = error.toString();
        } catch (InterruptedException stopped) {
            failure = stopped.toString();
            throw stopped;
        } finally {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            var calls = List.copyOf(relay.calls.subList(before, relay.calls.size()));
            var diagnostics = audit.events(chain).stream().filter(event -> event.eventId() > request
                            && event.type().equals("chat_context_compaction")).map(event -> event.data().getAsJsonObject()).toList();
            boolean reference = "reference".equals(phase) && delivered != null;
            int attempts = (int) calls.stream().filter(call -> call.get("operation").getAsString().equals("submit_chat_summary")).count();
            turns.add(new Turn(sample, side, phase, delivered == null ? Status.FAILED : Status.DELIVERED, failure, elapsed, delivered,
                    reference ? delivered.contains(relay.scenario.first()) && delivered.contains(relay.scenario.second()) : null,
                    reference && relay.scenario.equals(COAST) ? delivered.contains("潮汐") : null,
                    reference ? relay.scenario.waitTerms().stream().allMatch(delivered::contains) : null, attempts, calls, diagnostics));
        }
        return delivered != null;
    }

    /** 汇总分别使用各请求的真实计数字段；供应商漏报的量保持缺失，并给出影响的调用数。 */
    private static Map<String, Object> stats(List<Turn> all, String side) {
        var turns = all.stream().filter(t -> t.side().equals(side)).toList();
        var calls = turns.stream().flatMap(t -> t.calls().stream()).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("calls", calls.size());
        result.put("summaryCalls", turns.stream().mapToInt(Turn::attempts).sum());
        result.put("failedTurns", turns.stream().filter(t -> t.status() == Status.FAILED).count());
        result.put("unrunTurns", turns.stream().filter(t -> t.elapsedMs() == null).count());
        result.put("normalMs", percentiles(turns.stream().filter(t -> t.answer() != null && t.attempts() == 0).map(Turn::elapsedMs).toList()));
        result.put("triggerMs", percentiles(turns.stream().filter(t -> t.answer() != null && t.attempts() > 0).map(Turn::elapsedMs).toList()));
        for (String field : List.of("inputTokens", "cachedInputTokens", "outputTokens")) {
            int missing = (int) calls.stream().filter(call -> !call.has(field)).count();
            result.put(field + "MissingCalls", missing);
            result.put(field, missing == 0 ? calls.stream().mapToLong(call -> call.get(field).getAsLong()).sum() : null);
        }
        return result;
    }

    private static Map<String, ?> percentiles(List<Long> measurements) {
        var values = measurements.stream().sorted().toList();
        int n = values.size();
        if (n == 0) return Map.of("n", 0);
        return Map.of("n", n, "p50", values.get((n - 1) / 2), "p95", values.get((95 * n + 99) / 100 - 1));
    }

    /** 回环中继只转发本次模型请求的正文和授权头。凭据不会写入采样，缓存命中量始终单列。 */
    private static final class Relay implements AutoCloseable {
        private final List<JsonObject> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ChatWorkerConfig.Settings settings;
        private final HttpClient upstream;
        private final HttpServer server;
        private final BufferedWriter wire;
        private final Reference scenario;

        Relay(ChatWorkerConfig.Settings settings, HttpClient upstream, Path file, Reference scenario) throws IOException {
            this.settings = settings;
            this.upstream = upstream;
            this.scenario = scenario;
            wire = Files.newBufferedWriter(file);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::forward);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        }

        private void forward(HttpExchange exchange) throws IOException {
            try (exchange) {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                var request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                var network = HttpRequest.newBuilder(settings.endpoint()).version(settings.httpVersion()).timeout(settings.requestTimeout())
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) network.header("Authorization", auth);
                long start = System.nanoTime();
                var call = new JsonObject();
                call.addProperty("operation", ChatCompactionFixture.operation(request));
                call.addProperty("requestBytes", bytes.length);
                call.addProperty("requestSha256", sha256(bytes));
                String operation = call.get("operation").getAsString();
                var captured = new ChatCompactionFixture.Call(operation, request);
                if (scenario.equals(HOLDOUT)) {
                    var received = captured.messages();
                    var terms = new ArrayList<>(List.of(scenario.first(), scenario.second()));
                    terms.addAll(scenario.waitTerms());
                    var clues = new LinkedHashMap<String, Boolean>();
                    var dataClues = new LinkedHashMap<String, Boolean>();
                    // 系统规则也可能有“报告”这样的普通词，同步标出输入数据中真实出现的线索。
                    for (String term : terms) {
                        clues.put(term, received.stream().anyMatch(m -> m.text().contains(term)));
                        dataClues.put(term, received.stream().anyMatch(m -> !m.role().equals("system") && m.text().contains(term)));
                    }
                    call.add("inputClues", JSON.toJsonTree(clues));
                    call.add("inputDataClues", JSON.toJsonTree(dataClues));
                    call.addProperty("inputContainsOriginalStatement", received.stream().anyMatch(m -> m.text().contains(scenario.source())));
                }
                if ("plan_turn".equals(operation) || "reply".equals(operation)) {
                    var messages = captured.messages();
                    var history = messages.subList(1, messages.size() - 1);
                    call.addProperty("historyUnits", history.stream().mapToInt(m -> m.text().length()).sum());
                    call.addProperty("historyMessages", history.size());
                    call.addProperty("historyNamesBothPlaces", history.stream().anyMatch(m -> m.text().contains(scenario.first()))
                            && history.stream().anyMatch(m -> m.text().contains(scenario.second())));
                }
                byte[] content;
                int status;
                var wireRecord = new JsonObject();
                wireRecord.add("request", request);
                wireRecord.add("result", call);
                try {
                    var response = upstream.send(network.build(), HttpResponse.BodyHandlers.ofByteArray());
                    status = response.statusCode();
                    content = response.body();
                    if (status >= 200 && status < 300) {
                        try {
                            var parsed = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
                            if (parsed.isJsonObject()) {
                                var body = parsed.getAsJsonObject();
                                if (body.has("model")) call.add("responseModel", body.get("model"));
                                if (body.has("usage") && body.get("usage").isJsonObject()) copyUsage(body.getAsJsonObject("usage"), call);
                                if (request.has("tools")) wireRecord.add("providerChoices", toolChoices(body));
                            }
                        } catch (JsonParseException error) {
                            // 采样不改变供应商回包，正式适配器仍会收到原字节并按模型契约处理。
                            call.addProperty("responseParseError", error.toString());
                        }
                    }
                } catch (IOException | InterruptedException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    call.addProperty("transportError", error.toString());
                    status = 502;
                    content = "{}".getBytes(StandardCharsets.UTF_8);
                }
                call.addProperty("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                call.addProperty("httpStatus", status);
                calls.add(call);
                wire.write(JSON.toJson(wireRecord));
                wire.newLine();
                wire.flush();
                exchange.sendResponseHeaders(status, content.length);
                exchange.getResponseBody().write(content);
            }
        }

        /** 只在本次合成样本的 wire 文件留存受限工具的原始返回；保留错误字段类型供重查。 */
        private static JsonArray toolChoices(JsonObject body) {
            var choices = new JsonArray();
            if (!body.has("choices") || !body.get("choices").isJsonArray()) return choices;
            for (var candidate : body.getAsJsonArray("choices")) {
                var sample = new JsonObject();
                choices.add(sample);
                if (!candidate.isJsonObject()) continue;
                var choice = candidate.getAsJsonObject();
                sample.add("finish_reason", choice.get("finish_reason"));
                if (!choice.has("message") || !choice.get("message").isJsonObject()) continue;
                var message = choice.getAsJsonObject("message");
                if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) continue;
                var functions = new JsonArray();
                sample.add("tool_calls", functions);
                for (var item : message.getAsJsonArray("tool_calls")) {
                    var returned = new JsonObject();
                    functions.add(returned);
                    if (!item.isJsonObject()) continue;
                    var fn = item.getAsJsonObject().get("function");
                    if (fn != null && fn.isJsonObject()) {
                        returned.add("name", fn.getAsJsonObject().get("name"));
                        returned.add("arguments", fn.getAsJsonObject().get("arguments"));
                    }
                }
            }
            return choices;
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException error) {
                throw new AssertionError(error);
            }
        }

        private static void copyUsage(JsonObject usage, JsonObject call) {
            call.add("providerUsage", usage);
            copyCount(usage, "prompt_tokens", call, "inputTokens");
            copyCount(usage, "completion_tokens", call, "outputTokens");
            copyCount(usage, "prompt_cache_hit_tokens", call, "cachedInputTokens");
            if (!call.has("cachedInputTokens") && usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject())
                copyCount(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens", call, "cachedInputTokens");
        }

        private static void copyCount(JsonObject source, String key, JsonObject target, String field) {
            if (source.has(key) && !source.get(key).isJsonNull()) target.add(field, source.get(key));
        }

        @Override public void close() throws IOException { server.stop(0); wire.close(); }
    }
}
