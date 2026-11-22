package neuralcloud.controlplane.evaluation;

import neuralcloud.controlplane.orchestration.MeetingOrchestrator;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Action;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Outcome;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Result;
import neuralcloud.controlplane.orchestration.Orchestrator.Plan;
import neuralcloud.controlplane.orchestration.Orchestrator.Receipt;
import neuralcloud.controlplane.orchestration.Orchestrator.Scope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RouterEvaluation {
    // 只供会议回归使用，正式 Router 的工具枚举来自 controlplane.router.Route。
    public enum MeetingRoute { MEETING_RESCHEDULE, UNSUPPORTED }

    @FunctionalInterface
    public interface Classifier {
        MeetingRoute route(String text) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface DraftReply {
        String generate(String approvedText) throws IOException;
    }

    private final Classifier classifier;
    private final MasterEvaluation master;
    private final DraftReply draftReply;

    public RouterEvaluation(Classifier classifier, MasterEvaluation master) {
        this(classifier, master, null);
    }

    public RouterEvaluation(Classifier classifier, MasterEvaluation master, DraftReply draftReply) {
        this.classifier = classifier;
        this.master = master;
        this.draftReply = draftReply;
    }

    // completed 为 false 时，拒绝原因已作为 router_rejected 写入轨迹。replies 是按派发顺序交给用户的正文。
    public record Run(boolean completed, List<String> replies) {
    }

    public int run(Path tracePath, String caseId, Outcome simulatedOutcome, String routerModel, String masterModel)
            throws IOException, InterruptedException {
        try (var trace = new EvaluationTrace(tracePath)) {
            return run(trace, null, caseId, scenario(caseId).routeQuestion(), simulatedOutcome,
                    routerModel, masterModel).completed() ? 0 : 1;
        }
    }

    // question 是交给 Router 分流的当前问题；Master 取证仍使用该用例的上报与证据。
    public Run run(TraceSink trace, Integer cause, String caseId, String question, Outcome simulatedOutcome,
                   String routerModel, String masterModel) throws IOException, InterruptedException {
        var scenario = scenario(caseId);
        String workerMode = draftReply == null ? "SIMULATED" : "CHAT_DRAFT";
        int run = trace.write(caseId, cause, "router_run_started", Map.of(
                "routerModel", routerModel, "masterModel", masterModel, "workerMode", workerMode));
        int input = trace.write(caseId, run, "router_input", Map.of("text", question));
        MeetingRoute route;
        try {
            route = classifier.route(question);
        } catch (IOException error) {
            trace.write(caseId, input, "router_rejected", Map.of("detail", error.getMessage()));
            throw error;
        }
        int routed = trace.write(caseId, input, "router_route", Map.of("route", route));
        if (route != MeetingRoute.MEETING_RESCHEDULE) {
            trace.write(caseId, routed, "router_rejected", Map.of("detail", "本用例应选择会议改期流程"));
            return new Run(false, List.of());
        }
        // 取证仍走原有工具协议。挡板评分通过后才用接收的字段生成会议场景计划。
        var evaluated = master.evaluate(scenario, trace, routed);
        if (!"REVIEW_REQUIRED".equals(evaluated.category())) {
            trace.write(caseId, evaluated.eventId(), "router_rejected", Map.of("detail", evaluated.detail()));
            return new Run(false, List.of());
        }
        var values = evaluated.submitted();
        var intent = new MeetingOrchestrator.Intent(values.get("meetingTime").getAsString(),
                values.get("confirmMeetingNow").getAsBoolean(), values.get("verifyReminder").getAsBoolean(),
                values.get("waitReminderResult").getAsBoolean(), values.get("reportReminderFailure").getAsBoolean());
        var scope = new Scope(scenario.conversation(), UUID.randomUUID());
        Plan plan;
        try {
            plan = MeetingOrchestrator.plan(scope, intent);
        } catch (IllegalArgumentException error) {
            trace.write(caseId, evaluated.eventId(), "router_rejected", Map.of("detail", error.getMessage()));
            return new Run(false, List.of());
        }
        int planned = trace.write(caseId, evaluated.eventId(), "router_plan", plan);
        var workers = new ScriptedWorkers(trace, caseId, planned, simulatedOutcome, draftReply);
        new MeetingOrchestrator().execute(plan, intent, workers);
        trace.write(caseId, workers.event, "router_run_completed", Map.of("workerMode", workerMode));
        return new Run(true, List.copyOf(workers.replies));
    }

    // 用例须存在，并逐项要求生成计划所需的会议时间和四个行动字段。
    public void requireScenario(String caseId) throws IOException {
        scenario(caseId);
    }

    private MasterEvaluation.Scenario scenario(String caseId) throws IOException {
        var scenario = master.scenario(caseId);
        if (!scenario.requiredItems().containsAll(List.of("meetingTime", "confirmMeetingNow", "verifyReminder",
                "waitReminderResult", "reportReminderFailure"))) {
            throw new IOException("Router 挡板用例须逐项要求会议时间和四个行动字段");
        }
        return scenario;
    }

    // 模拟 Worker 只存在于显式挡板入口。回复正文与执行标识分开记录。
    private static final class ScriptedWorkers implements MeetingOrchestrator.Workers {
        private final TraceSink trace;
        private final String caseId;
        private final Outcome simulatedOutcome;
        private final DraftReply draftReply;
        private final List<String> replies = new ArrayList<>();
        private int event;

        ScriptedWorkers(TraceSink trace, String caseId, int event, Outcome simulatedOutcome, DraftReply draftReply) {
            this.trace = trace;
            this.caseId = caseId;
            this.event = event;
            this.simulatedOutcome = simulatedOutcome;
            this.draftReply = draftReply;
        }

        @Override
        public void reply(Scope scope, Action action, String text) throws IOException {
            int dispatch = dispatch(scope, action, "CHAT_WORKER", text);
            if (draftReply != null) {
                // 真实推理只生成草稿，渠道回执只能由实际发送路径产生。
                String draft = draftReply.generate(text);
                event = trace.write(caseId, dispatch, "chat_worker_draft", Map.of("action", action, "text", draft));
                replies.add(draft);
                System.out.println(draft);
                return;
            }
            event = trace.write(caseId, dispatch, "reply_receipt", Map.of("action", action, "simulated", true));
            replies.add(text);
            System.out.println(text);
        }

        @Override
        public Receipt<Result> checkReminder(Scope scope, String text) throws IOException {
            int dispatch = dispatch(scope, Action.CHECK_REMINDER, "TASK_WORKER", text);
            int waiting = trace.write(caseId, dispatch, "reminder_waiting", Map.of("action", Action.CHECK_REMINDER));
            event = trace.write(caseId, waiting, "reminder_receipt", Map.of("simulated", true,
                    "receipt", Map.of("scope", scope, "action", Action.CHECK_REMINDER, "outcome", simulatedOutcome)));
            return new Receipt<>(scope, Action.CHECK_REMINDER.name(), simulatedOutcome);
        }

        private int dispatch(Scope scope, Action action, String worker, String text) throws IOException {
            return trace.write(caseId, event, "worker_dispatch", Map.of(
                    "scope", scope, "action", action, "worker", worker, "text", text));
        }
    }
}
