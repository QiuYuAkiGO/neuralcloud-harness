package neuralcloud.controlplane.evaluation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
    private static final String END = "本轮结束。";
    private static final List<String> STOP = List.of("\n" + END);
    private static final String UNKNOWN_TIME = "我暂时无法确认会议时间。";
    private static final String NO_CITATIONS = "本轮无可引用证据。";
    private static final Pattern TIME = Pattern.compile("我确认会议定在(周[一二三四五六日] ?[0-9]{1,2} ?时)。");
    private static final Pattern CITATIONS = Pattern.compile("我引用证据([1-9][0-9]*(?:、[1-9][0-9]*)*)。");
    private static final Pattern IMPRESSION = Pattern.compile("我暂无印象。|我的印象是.+。");
    private static final Pattern INTENT = Pattern.compile("请.+。");
    private static final Pattern REASONING_MARKUP = Pattern.compile("(?i)</?think(?:\\s[^>]*)?>");

    private enum Kind {
        FACT("事实"), IMPRESSION("印象");
        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private enum EvidenceState { CURRENT, WITHDRAWN }

    private record Evidence(
            String id, int revision, Kind kind, EvidenceState state, String event,
            String venue, String visibleIn, String citationScope, String text) {
    }

    private record Expected(
            String time, String reminder, List<String> requiredEvidence,
            List<String> mustMention, List<String> forbiddenText) {
    }

    private record Scenario(
            String id, String topic, String conversation, String question, String observations,
            boolean factQueryFails, List<Evidence> evidence, Expected expected) {
        String query(Kind kind) {
            return "请查找当前会话中关于" + topic + "的" + kind.label + "。";
        }
    }

    private record Suite(String version, int maxTokens, double temperature, List<Scenario> cases) {
    }

    private record ReturnedEvidence(int label, Evidence evidence, int returnEventId) {
    }

    private record ToolAnswer(String outcome, String text, List<Evidence> evidence) {
    }

    private record Result(String category, String detail) {
    }

    @FunctionalInterface
    interface Inference {
        Master.Completion complete(String prompt, int maxTokens, double temperature, List<String> stop)
                throws IOException, InterruptedException;
    }

    private final Inference inference;
    private final Suite suite;
    private final String rolePrompt;

    public MasterEvaluation(Master master, Path fixtures, String rolePrompt) throws IOException {
        this(master::complete, fixtures, rolePrompt);
    }

    MasterEvaluation(Inference inference, Path fixtures, String rolePrompt) throws IOException {
        if (rolePrompt.isBlank()) {
            throw new IOException("Master 职责提示词不能为空");
        }
        this.inference = inference;
        this.rolePrompt = rolePrompt.strip();
        try (var reader = Files.newBufferedReader(fixtures)) {
            suite = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(reader, Suite.class);
        } catch (JsonParseException error) {
            throw new IOException("挡板样例文件格式无效 " + fixtures, error);
        }
        if (suite == null || suite.version() == null || suite.version().isBlank() || suite.maxTokens() <= 0
                || !Double.isFinite(suite.temperature()) || suite.temperature() < 0 || suite.cases() == null
                || suite.cases().isEmpty() || suite.cases().stream().anyMatch(
                        scenario -> scenario == null || scenario.id() == null || scenario.id().isBlank())
                || suite.cases().stream().map(Scenario::id).distinct().count() != suite.cases().size()) {
            throw new IOException("挡板样例须声明版本、推理参数和非空且唯一的用例标识 " + fixtures);
        }
    }

    public int run(Path tracePath, Master.Settings settings) throws IOException, InterruptedException {
        int passed = 0;
        try (var trace = new EvaluationTrace(tracePath)) {
            int runEvent = trace.write(null, null, "run_started", Map.of(
                    "fixtureVersion", suite.version(), "model", settings.model(), "endpoint", settings.endpoint().toString(),
                    "maxTokens", suite.maxTokens(), "temperature", suite.temperature(), "stop", STOP,
                    "promptVersion", "meeting-transcript-v1-role-v1", "httpVersion", settings.httpVersion().name()));
            for (Scenario scenario : suite.cases()) {
                Result result = evaluate(scenario, trace, runEvent);
                if (result.category().equals("REVIEW_REQUIRED")) {
                    passed++;
                }
                System.out.println(scenario.id() + " " + result.category() + " " + result.detail());
            }
            trace.write(null, runEvent, "run_completed", Map.of("cases", suite.cases().size(), "automatedPassed", passed,
                    "manualReviewRequired", passed));
        }
        System.out.printf("自动检查 %d/%d，待人工复核 %d，轨迹 %s%n",
                passed, suite.cases().size(), passed, tracePath);
        return passed == suite.cases().size() ? 0 : 1;
    }

    private Result evaluate(Scenario scenario, EvaluationTrace trace, int runEvent)
            throws IOException, InterruptedException {
        String id = scenario.id();
        int event = trace.write(id, runEvent, "router_report", Map.of("conversation", scenario.conversation(),
                "topic", scenario.topic(), "question", scenario.question(), "observations", scenario.observations()));
        var queried = EnumSet.noneOf(Kind.class);
        var returned = new LinkedHashMap<Integer, ReturnedEvidence>();
        List<Integer> resultEvents = new ArrayList<>();
        var conversation = new StringBuilder(prompt(scenario));
        // 两类查询每类一次，加一轮决策。非法输出留在原始轨迹，挡板不给模型代查或补写。
        for (int turn = 0; turn <= Kind.values().length; turn++) {
            String input = conversation.toString();
            int inputEvent = trace.write(id, event, "model_input", Map.of(
                    "text", input, "memoryResultEventIds", resultEvents));
            Master.Completion response;
            try {
                response = inference.complete(input, suite.maxTokens(), suite.temperature(), STOP);
            } catch (IOException error) {
                return finish(trace, id, inputEvent, "MODEL_TRANSPORT", error.getMessage());
            }
            if (REASONING_MARKUP.matcher(response.text()).find()) {
                // 决策轨迹只收集对外正文。含推理标记的输出按协议失败记录，不保存正文。
                int withheld = trace.write(id, inputEvent, "model_output", Map.of(
                        "withheld", "reasoning_markup", "finishReason", response.finishReason()));
                return finish(trace, id, withheld, "OUTPUT_CONTRACT", "输出包含推理标记");
            }
            event = trace.write(id, inputEvent, "model_output", response);
            List<String> lines = response.text().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            List<Kind> requests = new ArrayList<>();
            for (String line : lines) {
                for (Kind kind : Kind.values()) {
                    if (line.equals(scenario.query(kind))) {
                        requests.add(kind);
                    }
                }
            }
            if (!requests.isEmpty()) {
                if (requests.size() != lines.size()) {
                    return finish(trace, id, event, "OUTPUT_CONTRACT", "查询与其他正文混合");
                }
                conversation.append(String.join("\n", lines)).append("\n");
                int modelOutput = event;
                for (Kind kind : requests) {
                    if (!queried.add(kind)) {
                        return finish(trace, id, event, "RETRIEVAL", "重复查询" + kind.label);
                    }
                    int requestEvent = trace.write(id, modelOutput, "memory_query", Map.of(
                            "kind", kind.label, "conversation", scenario.conversation(), "text", scenario.query(kind)));
                    ToolAnswer answer = lookup(scenario, kind, returned.size());
                    List<Map<String, Object>> records = new ArrayList<>();
                    int next = returned.size();
                    for (Evidence item : answer.evidence()) {
                        records.add(Map.of("label", ++next, "id", item.id(), "revision", item.revision(),
                                "sourceEvent", item.event(), "visibleIn", item.visibleIn(),
                                "citationScope", item.citationScope(), "state", item.state()));
                    }
                    event = trace.write(id, requestEvent, "memory_result", Map.of(
                            "outcome", answer.outcome(), "text", answer.text(), "evidence", records));
                    resultEvents.add(event);
                    for (Evidence item : answer.evidence()) {
                        int label = returned.size() + 1;
                        returned.put(label, new ReturnedEvidence(label, item, event));
                    }
                    conversation.append(answer.text()).append("\n");
                }
                conversation.append("Master：\n");
                continue;
            }
            if (lines.size() != 5 || !isDecision(lines)) {
                return finish(trace, id, event, "OUTPUT_CONTRACT", "输出未满足查询或五句决策契约");
            }
            int decision = trace.write(id, event, "decision", Map.of("conclusion", lines.get(0),
                    "reminder", lines.get(1), "citation", lines.get(2), "impression", lines.get(3), "intent", lines.get(4)));
            Result result = assess(scenario.expected(), queried, lines, returned, trace, id, decision);
            return finish(trace, id, decision, result.category(), result.detail());
        }
        return finish(trace, id, event, "RETRIEVAL", "查询后没有提交决策");
    }

    private static boolean isDecision(List<String> lines) {
        return (UNKNOWN_TIME.equals(lines.get(0)) || TIME.matcher(lines.get(0)).matches())
                && List.of("旧提醒待核实。", "旧提醒仍存在。", "旧提醒已取消。").contains(lines.get(1))
                && (NO_CITATIONS.equals(lines.get(2)) || CITATIONS.matcher(lines.get(2)).matches())
                && IMPRESSION.matcher(lines.get(3)).matches() && INTENT.matcher(lines.get(4)).matches();
    }

    private static ToolAnswer lookup(Scenario scenario, Kind kind, int previous) {
        if (kind == Kind.FACT && scenario.factQueryFails()) {
            return new ToolAnswer("FAILED", "事实查询失败，暂时无法核实。", List.of());
        }
        List<Evidence> visible = scenario.evidence().stream()
                .filter(item -> item.kind() == kind && item.visibleIn().equals(scenario.conversation())).toList();
        List<Evidence> current = visible.stream().filter(item -> item.state() == EvidenceState.CURRENT).toList();
        if (current.isEmpty()) {
            if (!visible.isEmpty()) {
                return new ToolAnswer("WITHDRAWN", "事实查询发现一条更正已撤回，当前没有可引用的更正。", List.of());
            }
            return new ToolAnswer("NO_MATCH", kind.label + "查询成功，未查到匹配记录。", List.of());
        }
        var message = new StringBuilder(kind.label).append("查询成功。");
        for (Evidence item : current) {
            message.append("\n证据").append(++previous).append("来自").append(item.event()).append("。")
                    .append(item.venue()).append(item.text()).append("。这条记忆仅可在")
                    .append(item.citationScope()).append("引用。");
        }
        return new ToolAnswer("MATCH", message.toString(), current);
    }

    private static Result assess(Expected expected, EnumSet<Kind> queried, List<String> lines,
                                 Map<Integer, ReturnedEvidence> returned, EvaluationTrace trace, String id, int decision)
            throws IOException {
        if (!queried.containsAll(EnumSet.allOf(Kind.class))) {
            return new Result("RETRIEVAL", "决策之前未查询事实和印象");
        }
        var citations = CITATIONS.matcher(lines.get(2));
        var linked = new ArrayList<Map<String, Object>>();
        if (citations.matches()) {
            for (String number : citations.group(1).split("、")) {
                int label;
                try {
                    label = Integer.parseInt(number);
                } catch (NumberFormatException error) {
                    return new Result("EVIDENCE", "引用编号超出范围");
                }
                ReturnedEvidence item = returned.get(label);
                if (item == null) {
                    return new Result("EVIDENCE", "引用了本轮未获准返回的证据 " + label);
                }
                linked.add(Map.of("label", item.label(), "id", item.evidence().id(),
                        "revision", item.evidence().revision(), "returnEventId", item.returnEventId()));
            }
        }
        trace.write(id, decision, "decision_evidence", linked);
        var time = TIME.matcher(lines.get(0));
        String actualTime = time.matches() ? normalize(time.group(1)) : "未知";
        if (!normalize(expected.time()).equals(actualTime)) {
            return new Result("EVIDENCE", "会议时间或不确定状态与可用事实不一致");
        }
        if (!linked.stream().map(item -> item.get("id")).toList().containsAll(expected.requiredEvidence())) {
            return new Result("EVIDENCE", "遗漏支撑结论的必要证据");
        }
        String actualReminder = lines.get(1);
        if (!expected.reminder().equals(actualReminder)) {
            return new Result("DECISION", "提醒状态与核对结果或回执不一致");
        }
        String text = normalize(String.join("\n", lines));
        for (String forbidden : expected.forbiddenText()) {
            if (text.contains(normalize(forbidden))) {
                return new Result("EVIDENCE", "输出包含当前会话禁止使用的内容");
            }
        }
        String intent = normalize(lines.get(4));
        List<String> missing = expected.mustMention().stream().filter(word -> !intent.contains(normalize(word))).toList();
        if (!missing.isEmpty()) {
            return new Result("DECISION", "缺少当前情况对应的行动或说明 " + missing);
        }
        return new Result("REVIEW_REQUIRED", "自动检查通过，待复核证据语义与行动依赖");
    }

    private static Result finish(EvaluationTrace trace, String id, int event, String category, String detail)
            throws IOException {
        Result result = new Result(category, detail);
        trace.write(id, event, "case_result", result);
        return result;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }

    private String prompt(Scenario scenario) {
        // 职责提示可独立替换，挡板阶段的句式和示范保留原样，便于比较。
        return rolePrompt + "\n\n" + """
                只写对外发出的查询或决策正文。
                本次允许的查询句如下。可以在一轮内发出两句，也可每轮发出一句。
                %s
                %s
                只引用本轮工具实际返回且适用的证据。Worker 印象和工具尝试不能证明取消成功。
                决策写五句，每句一行。时间用周几几时。时间未知写“我暂时无法确认会议时间。”
                提醒状态三选一：“旧提醒待核实。”“旧提醒仍存在。”“旧提醒已取消。”
                引用多个编号时用顿号。没有证据写“本轮无可引用证据。”
                印象要交代提出者。没有印象写“我暂无印象。”
                行动意图以“请”开头。证据不足时说明原因并提出核实。
                每轮查询或决策之后，另起一行写“本轮结束。”

                以下是另一个会话的示范。
                Router：活动群问演练改在何时，旧提醒是否取消？
                Master：
                请查找当前会话中关于演练改期的事实。
                请查找当前会话中关于演练改期的印象。
                本轮结束。
                事实查询成功。
                证据1来自活动群公告。演练改定为周三13时。
                印象查询成功，未查到匹配记录。
                Master：
                我确认会议定在周三13时。
                旧提醒待核实。
                我引用证据1。
                我暂无印象。
                请确认改期并核对旧提醒，等核对有结果后再告知提醒状态。
                本轮结束。

                现在开始新的会话，证据编号从1重新计算。
                Router：当前是%s。用户问：“%s”
                %s
                Master：
                """.formatted(scenario.query(Kind.FACT), scenario.query(Kind.IMPRESSION),
                scenario.conversation(), scenario.question(), scenario.observations());
    }
}
