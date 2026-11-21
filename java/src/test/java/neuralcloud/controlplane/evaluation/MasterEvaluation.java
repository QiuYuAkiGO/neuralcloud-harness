package neuralcloud.controlplane.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MasterEvaluation {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String QUERY = "query_memory";
    private static final String SUBMIT = "submit_results";
    private static final Pattern REASONING_MARKUP = Pattern.compile("(?i)<(/?)think(?:\\s[^>]*)?(?:>|\\z)");

    private enum Kind {
        FACT("事实"), IMPRESSION("印象");
        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private enum EvidenceState { CURRENT, WITHDRAWN }

    private enum QueryOutcome { EMPTY, MISMATCH, MATCH, WITHDRAWN, FAILED }

    private record Evidence(
            String id, int revision, Kind kind, EvidenceState state, String event,
            String venue, String visibleIn, String citationScope, String topic, String text) {
    }

    private record Expected(Map<String, JsonElement> values, List<String> requiredEvidence,
                            List<String> forbiddenText) {
    }

    record Scenario(
            String id, String topic, String conversation, String question, String observations,
            boolean factQueryFails, boolean javaHandlesEmpty, List<Evidence> evidence,
            List<String> requiredItems, Expected expected) {
        String report() {
            return "当前会话：" + conversation + "。事项：" + topic + "。用户问：" + question + "\n" + observations;
        }

        String routeQuestion() {
            return "用户问：" + question + "\n" + observations;
        }
    }

    private record Suite(String version, int maxTokens, double temperature,
                         Map<String, Item> resultItems, List<Scenario> cases) {
    }

    private record Item(String description, String type, List<String> allowed, String elementType) {
        Map<String, Object> schema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("description", description);
            schema.put("type", type);
            if ("array".equals(type)) {
                schema.put("items", allowed == null
                        ? Map.of("type", elementType) : Map.of("type", elementType, "enum", allowed));
            } else if (allowed != null) {
                schema.put("enum", allowed);
            }
            return schema;
        }

        boolean accepts(JsonElement value) {
            return switch (type) {
                case "string" -> value instanceof JsonPrimitive text && text.isString()
                        && !text.getAsString().isBlank() && (allowed == null || allowed.contains(text.getAsString()));
                case "boolean" -> value instanceof JsonPrimitive flag && flag.isBoolean();
                case "array" -> value instanceof JsonArray array && array.asList().stream().allMatch(this::acceptsElement)
                        && array.asList().stream().distinct().count() == array.size();
                default -> false;
            };
        }

        private boolean acceptsElement(JsonElement value) {
            if (!(value instanceof JsonPrimitive scalar)) {
                return false;
            }
            if ("integer".equals(elementType)) {
                try {
                    return scalar.isNumber() && scalar.getAsBigDecimal().intValueExact() > 0;
                } catch (ArithmeticException | NumberFormatException error) {
                    return false;
                }
            }
            return scalar.isString() && allowed.contains(scalar.getAsString());
        }
    }

    private record ReturnedEvidence(int label, Evidence evidence, int returnEventId) {
    }

    private record ToolAnswer(QueryOutcome outcome, String text, List<Evidence> evidence) {
    }

    private record Result(String category, String detail) {
    }

    record Evaluation(String category, String detail, int eventId, Map<String, JsonElement> submitted) {
    }

    private record FilteredOutput(String text, int markerCount, int unclosedOpenings, int unexpectedClosings) {
    }

    private record Turn(Master.ChatTurn response, String visibleText, int event) {
    }

    @FunctionalInterface
    interface Inference {
        Master.ChatTurn chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                             int maxTokens, double temperature) throws IOException, InterruptedException;
    }

    private final Inference inference;
    private final Suite suite;
    private final String rolePrompt;

    public MasterEvaluation(Master master, Path fixtures, String rolePrompt) throws IOException {
        this(master::chat, fixtures, rolePrompt);
    }

    MasterEvaluation(Inference inference, Path fixtures, String rolePrompt) throws IOException {
        if (rolePrompt.isBlank()) {
            throw new IOException("Master 职责提示词不能为空");
        }
        this.inference = inference;
        this.rolePrompt = rolePrompt.strip();
        try (var reader = Files.newBufferedReader(fixtures)) {
            suite = JSON.fromJson(reader, Suite.class);
        } catch (JsonParseException error) {
            throw new IOException("挡板样例文件格式无效 " + fixtures, error);
        }
        if (suite == null || !"meeting-v3".equals(suite.version()) || suite.maxTokens() <= 0
                || !Double.isFinite(suite.temperature()) || suite.temperature() < 0 || suite.resultItems() == null
                || suite.cases() == null || suite.cases().isEmpty()
                || suite.cases().stream().anyMatch(scenario -> scenario == null || scenario.id() == null
                        || scenario.id().isBlank() || scenario.requiredItems() == null || scenario.requiredItems().isEmpty()
                        || !suite.resultItems().keySet().containsAll(scenario.requiredItems()))
                || suite.cases().stream().map(Scenario::id).distinct().count() != suite.cases().size()) {
            throw new IOException("挡板样例须满足 meeting-v3 版本、推理参数与必交事项契约 " + fixtures);
        }
    }

    public int run(Path tracePath, Master.Settings settings) throws IOException, InterruptedException {
        int passed = 0;
        int javaHandled = 0;
        try (var trace = new EvaluationTrace(tracePath)) {
            int runEvent = trace.write(null, null, "run_started", Map.of(
                    "fixtureVersion", suite.version(), "model", settings.model(), "endpoint", settings.endpoint().toString(),
                    "maxTokens", suite.maxTokens(), "temperature", suite.temperature(),
                    "promptVersion", "meeting-tools-v1-role-v2", "httpVersion", settings.httpVersion().name(),
                    "inference", Map.of("mode", "chat", "enableThinking", settings.enableThinking())));
            for (Scenario scenario : suite.cases()) {
                Evaluation result = evaluate(scenario, trace, runEvent);
                if (result.category().equals("REVIEW_REQUIRED")) {
                    passed++;
                } else if (result.category().equals("JAVA_HANDLED")) {
                    javaHandled++;
                }
                System.out.println(scenario.id() + " " + result.category() + " " + result.detail());
            }
            trace.write(null, runEvent, "run_completed", Map.of("cases", suite.cases().size(), "automatedPassed", passed,
                    "javaHandled", javaHandled, "manualReviewRequired", passed));
        }
        System.out.printf("Master 自动检查通过 %d，Java 处理 %d，总用例 %d，轨迹 %s%n",
                passed, javaHandled, suite.cases().size(), tracePath);
        return passed + javaHandled == suite.cases().size() ? 0 : 1;
    }

    Scenario scenario(String id) throws IOException {
        return suite.cases().stream().filter(scenario -> scenario.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("找不到挡板用例 " + id));
    }

    Evaluation evaluate(Scenario scenario, TraceSink trace, int runEvent)
            throws IOException, InterruptedException {
        String id = scenario.id();
        String report = scenario.report();
        int event = trace.write(id, runEvent, "router_report", Map.of("text", report,
                "conversation", scenario.conversation(), "topic", scenario.topic()));
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", rolePrompt));
        messages.add(Map.of("role", "user", "content", report
                + "\n先调用 query_memory 查询本事项的 FACT（事实）和 IMPRESSION（印象），可一次查询两类。"));
        var queried = EnumSet.noneOf(Kind.class);
        var returned = new LinkedHashMap<Integer, ReturnedEvidence>();
        List<Integer> resultEvents = new ArrayList<>();
        List<Map<String, Object>> queryTools = List.of(tool(QUERY, "读取当前事项的可用记忆",
                Map.of("sources", Map.of("type", "array", "minItems", 1, "uniqueItems", true,
                        "items", Map.of("type", "string", "enum", List.of("FACT", "IMPRESSION")))),
                List.of("sources")));
        int emptyCount = 0;
        // 查询由模型发起，工具只在当前会话内提供实际获准使用的记录。
        for (int turnNumber = 1; turnNumber <= Kind.values().length && queried.size() < Kind.values().length; turnNumber++) {
            Turn turn = exchange(scenario, trace, event, messages, queryTools, "retrieval", turnNumber);
            if (turn.response() == null) {
                return finish(trace, id, turn.event(), "MODEL_TRANSPORT", turn.visibleText());
            }
            event = turn.event();
            Master.ToolCall call = onlyCall(turn, QUERY);
            JsonObject args = arguments(call);
            EnumSet<Kind> sources = querySources(args);
            if (sources.isEmpty() || sources.stream().anyMatch(queried::contains)) {
                event = trace.write(id, event, "query_retry", Map.of("alreadyQueried", queried));
                appendVisible(messages, turn.visibleText());
                messages.add(Map.of("role", "user", "content",
                        "请调用 query_memory，只提交还没查询的来源。已查询：" + queried));
                continue;
            }

            List<String> answers = new ArrayList<>();
            for (Kind kind : sources) {
                queried.add(kind);
                int request = trace.write(id, turn.event(), "memory_query", Map.of(
                        "kind", kind.label, "conversation", scenario.conversation(), "toolCallId", call.id()));
                ToolAnswer answer = lookup(scenario, kind, returned.size());
                if (answer.outcome() == QueryOutcome.EMPTY) {
                    emptyCount++;
                }
                List<Map<String, Object>> records = new ArrayList<>();
                int label = returned.size();
                for (Evidence item : answer.evidence()) {
                    records.add(Map.of("label", ++label, "id", item.id(), "revision", item.revision(),
                            "sourceEvent", item.event(), "visibleIn", item.visibleIn(),
                            "citationScope", item.citationScope(), "state", item.state()));
                }
                event = trace.write(id, request, "memory_result", Map.of(
                        "outcome", answer.outcome(), "text", answer.text(), "evidence", records));
                resultEvents.add(event);
                for (Evidence item : answer.evidence()) {
                    int number = returned.size() + 1;
                    returned.put(number, new ReturnedEvidence(number, item, event));
                }
                answers.add(answer.text());
            }
            appendToolExchange(messages, turn.visibleText(), call, args, String.join("\n", answers));
        }
        if (queried.size() != Kind.values().length) {
            return finish(trace, id, event, "RETRIEVAL", "未完成事实与印象查询");
        }
        if (scenario.javaHandlesEmpty() && emptyCount == Kind.values().length) {
            int handled = trace.write(id, event, "java_no_data", Map.of(
                    "memoryResultEventIds", resultEvents, "outcome", QueryOutcome.EMPTY));
            return finish(trace, id, handled, "JAVA_HANDLED", "两类查询无可用数据，Java 结束本次查询");
        }

        var submitted = new LinkedHashMap<String, JsonElement>();
        List<String> pending = scenario.requiredItems();
        // 第二轮只收缺项。已接收的字段保持原值，两个回合通过事件 ID 关联。
        for (int round = 1; round <= 2; round++) {
            var fields = new LinkedHashMap<String, Object>();
            pending.forEach(name -> fields.put(name, suite.resultItems().get(name).schema()));
            event = trace.write(id, event, round == 1 ? "required_results" : "submission_retry",
                    Map.of("round", round, "items", pending));
            messages.add(Map.of("role", "user", "content", (round == 1 ? "请调用 submit_results 逐项提交必交结果："
                    : "上一轮还有缺项。请调用 submit_results，仅补交这些事项：") + String.join("、", pending)));
            Turn turn = exchange(scenario, trace, event, messages, List.of(tool(SUBMIT,
                    "提交 Master 的判断、证据和行动意图。各字段均须给出。", fields, pending)), "submission", round);
            if (turn.response() == null) {
                return finish(trace, id, turn.event(), "MODEL_TRANSPORT", turn.visibleText());
            }
            Master.ToolCall call = onlyCall(turn, SUBMIT);
            JsonObject input = arguments(call);
            JsonObject accepted = new JsonObject();
            List<String> invalid = new ArrayList<>();
            List<String> requested = pending;
            int unexpected = input == null ? 0 : (int) input.keySet().stream()
                    .filter(field -> !requested.contains(field)).count();
            if (unexpected > 0) {
                // 越界字段和已提交字段都由调用方修正，不能在第二轮覆盖。
                input = null;
            }
            for (String field : pending) {
                if (input == null || !input.has(field)) {
                    continue;
                }
                JsonElement value = input.get(field);
                if (value instanceof JsonPrimitive scalar && scalar.isString()) {
                    FilteredOutput visible = filterReasoning(scalar.getAsString());
                    if (visible.markerCount() > 0) {
                        trace.write(id, turn.event(), "reasoning_filtered", Map.of(
                                "toolArgumentField", field, "markerCount", visible.markerCount(),
                                "unclosedOpenings", visible.unclosedOpenings(), "unexpectedClosings", visible.unexpectedClosings()));
                    }
                    value = new JsonPrimitive(visible.text().strip());
                }
                if (!suite.resultItems().get(field).accepts(value)) {
                    invalid.add(field);
                    continue;
                }
                accepted.add(field, value);
                submitted.put(field, value);
            }
            pending = scenario.requiredItems().stream().filter(field -> !submitted.containsKey(field)).toList();
            event = trace.write(id, turn.event(), "result_submission", Map.of(
                    "round", round, "toolCallId", call == null ? "" : call.id(), "accepted", accepted,
                    "invalid", invalid, "unexpected", unexpected, "missing", pending));
            if (pending.isEmpty()) {
                int decision = trace.write(id, event, "decision", submitted);
                Result result = assess(scenario.expected(), submitted, returned, trace, id, decision);
                int resultEvent = trace.write(id, decision, "case_result", result);
                return new Evaluation(result.category(), result.detail(), resultEvent, Map.copyOf(submitted));
            }
            if (input != null) {
                appendToolExchange(messages, turn.visibleText(), call, accepted,
                        "已接收：" + accepted.keySet() + "。待补交：" + pending + "。格式有误：" + invalid);
            } else {
                appendVisible(messages, turn.visibleText());
                if (unexpected > 0) {
                    messages.add(Map.of("role", "user", "content", "上轮提交包含未要求的字段，请仅提交当前待补事项。"));
                }
            }
        }
        return finish(trace, id, event, "INCOMPLETE_RESULTS", "两轮提交后仍缺少 " + pending);
    }

    private Turn exchange(Scenario scenario, TraceSink trace, int cause, List<Map<String, Object>> messages,
                          List<Map<String, Object>> tools, String phase, int round)
            throws IOException, InterruptedException {
        String id = scenario.id();
        int input = trace.write(id, cause, "model_input", Map.of(
                "phase", phase, "round", round, "messages", messages, "tools", tools));
        Master.ChatTurn response;
        try {
            response = inference.chat(messages, tools, suite.maxTokens(), suite.temperature());
        } catch (IOException error) {
            System.err.println("Master 推理调用失败，挡板用例 " + id);
            error.printStackTrace(System.err);
            return new Turn(null, error.getMessage(), input);
        }
        var visible = filterReasoning(response.content().text());
        int parent = input;
        if (visible.markerCount() > 0 || response.content().reasoningFieldPresent()) {
            parent = trace.write(id, parent, "reasoning_filtered", Map.of(
                    "markerCount", visible.markerCount(), "unclosedOpenings", visible.unclosedOpenings(),
                    "unexpectedClosings", visible.unexpectedClosings(),
                    "reasoningFieldPresent", response.content().reasoningFieldPresent()));
        }
        int output = trace.write(id, parent, "model_output", Map.of("text", visible.text(),
                "finishReason", response.content().finishReason(), "toolCallCount", response.calls().size()));
        return new Turn(response, visible.text(), output);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
                                            List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", properties,
                        "required", required, "additionalProperties", false)));
    }

    private static Master.ToolCall onlyCall(Turn turn, String name) {
        var calls = turn.response().calls();
        return calls.size() == 1 && name.equals(calls.getFirst().name()) ? calls.getFirst() : null;
    }

    private static JsonObject arguments(Master.ToolCall call) {
        if (call != null) {
            try {
                return JSON.fromJson(call.arguments(), JsonObject.class);
            } catch (JsonParseException error) {
                return null;
            }
        }
        return null;
    }

    private static EnumSet<Kind> querySources(JsonObject input) {
        var sources = EnumSet.noneOf(Kind.class);
        if (input == null || input.size() != 1 || !(input.get("sources") instanceof JsonArray requested)) {
            return sources;
        }
        for (JsonElement element : requested) {
            if (!(element instanceof JsonPrimitive value) || !value.isString()
                    || (!"FACT".equals(value.getAsString()) && !"IMPRESSION".equals(value.getAsString()))
                    || !sources.add(Kind.valueOf(value.getAsString()))) {
                return EnumSet.noneOf(Kind.class);
            }
        }
        return sources;
    }

    private static void appendVisible(List<Map<String, Object>> messages, String text) {
        if (!text.isBlank()) {
            messages.add(Map.of("role", "assistant", "content", text));
        }
    }

    private static void appendToolExchange(List<Map<String, Object>> messages, String text, Master.ToolCall call,
                                           JsonObject safeArguments, String output) {
        messages.add(Map.of("role", "assistant", "content", text, "tool_calls", List.of(Map.of(
                "id", call.id(), "type", "function",
                "function", Map.of("name", call.name(), "arguments", JSON.toJson(safeArguments))))));
        messages.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", output));
    }

    private static Result assess(Expected expected, Map<String, JsonElement> submitted,
                                  Map<Integer, ReturnedEvidence> returned, TraceSink trace, String id, int decision)
            throws IOException {
        var linked = new ArrayList<Map<String, Object>>();
        if (submitted.get("evidenceLabels") instanceof JsonArray labels) {
            for (JsonElement value : labels) {
                ReturnedEvidence item = returned.get(value.getAsInt());
                if (item == null) {
                    return new Result("EVIDENCE", "引用了本轮未获准返回的证据 " + value);
                }
                linked.add(Map.of("label", item.label(), "id", item.evidence().id(),
                        "revision", item.evidence().revision(), "returnEventId", item.returnEventId()));
            }
        }
        trace.write(id, decision, "decision_evidence", linked);
        String combined = normalize(submitted.toString());
        if (expected.forbiddenText().stream().anyMatch(text -> combined.contains(normalize(text)))) {
            return new Result("EVIDENCE", "结果包含当前会话禁止使用的内容");
        }
        if (!linked.stream().map(entry -> entry.get("id")).toList().containsAll(expected.requiredEvidence())) {
            return new Result("EVIDENCE", "遗漏支撑结论的必要证据");
        }
        for (var expectedValue : expected.values().entrySet()) {
            JsonElement actual = submitted.get(expectedValue.getKey());
            if (actual == null || !normalize(expectedValue.getValue().getAsString()).equals(normalize(actual.getAsString()))) {
                return new Result("DECISION", "提交的结果与证据不符：" + expectedValue.getKey());
            }
        }
        return new Result("REVIEW_REQUIRED", "必交结果和自动检查已通过，待复核解释与勾选的行动是否一致");
    }

    private static Evaluation finish(TraceSink trace, String id, int event, String category, String detail)
            throws IOException {
        Result result = new Result(category, detail);
        return new Evaluation(category, detail, trace.write(id, event, "case_result", result), Map.of());
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static FilteredOutput filterReasoning(String text) {
        var tags = REASONING_MARKUP.matcher(text);
        var visible = new StringBuilder();
        int previous = 0;
        int depth = 0;
        int markerCount = 0;
        int unexpectedClosings = 0;
        while (tags.find()) {
            markerCount++;
            if (depth == 0) {
                visible.append(text, previous, tags.start());
            }
            if (tags.group(1).isEmpty()) {
                depth++;
            } else if (depth == 0) {
                // 孤立闭合标记前的内容可能是后端省略了起始标记的推理片段。
                visible.setLength(0);
                unexpectedClosings++;
            } else if (--depth == 0 && !visible.isEmpty()) {
                visible.append('\n');
            }
            previous = tags.end();
        }
        if (depth == 0) {
            visible.append(text, previous, text.length());
        }
        return new FilteredOutput(visible.toString(), markerCount, depth, unexpectedClosings);
    }

    private static ToolAnswer lookup(Scenario scenario, Kind kind, int previous) {
        if (kind == Kind.FACT && scenario.factQueryFails()) {
            return new ToolAnswer(QueryOutcome.FAILED, "事实查询调用失败，暂时无法核实。", List.of());
        }
        List<Evidence> visible = scenario.evidence().stream()
                .filter(item -> item.kind() == kind && item.visibleIn().equals(scenario.conversation())).toList();
        // 旧样例只包含所属事项的证据，未显式标注 topic。
        List<Evidence> relevant = visible.stream()
                .filter(item -> item.topic() == null || scenario.topic().equals(item.topic())).toList();
        List<Evidence> current = relevant.stream().filter(item -> item.state() == EvidenceState.CURRENT).toList();
        if (current.isEmpty()) {
            if (!relevant.isEmpty()) {
                return new ToolAnswer(QueryOutcome.WITHDRAWN, "事实查询发现一条更正已撤回，当前没有可引用的更正。", List.of());
            }
            List<String> otherTopics = visible.stream().filter(item -> item.topic() != null
                            && item.state() == EvidenceState.CURRENT)
                    .map(Evidence::topic).distinct().toList();
            if (!otherTopics.isEmpty()) {
                return new ToolAnswer(QueryOutcome.MISMATCH,
                        kind.label + "查询返回了关于" + String.join("、", otherTopics)
                                + "的记录，与" + scenario.topic() + "不匹配，无法作为依据。", List.of());
            }
            return new ToolAnswer(QueryOutcome.EMPTY, kind.label + "查询没有可用数据。", List.of());
        }
        var message = new StringBuilder(kind.label).append("查询成功。");
        for (Evidence item : current) {
            message.append("\n证据").append(++previous).append("来自").append(item.event()).append("。")
                    .append(item.venue()).append(item.text()).append("。这条记忆仅可在")
                    .append(item.citationScope()).append("引用。");
        }
        return new ToolAnswer(QueryOutcome.MATCH, message.toString(), current);
    }

}
