package neuralcloud.controlplane.evaluation;

import neuralcloud.controlplane.orchestration.MeetingOrchestrator;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Action;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Outcome;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Result;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Route;
import neuralcloud.controlplane.orchestration.Orchestrator.Plan;
import neuralcloud.controlplane.orchestration.Orchestrator.Receipt;
import neuralcloud.controlplane.orchestration.Orchestrator.Scope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RouterEvaluation {
    @FunctionalInterface
    public interface Classifier {
        Route route(String text) throws IOException, InterruptedException;
    }

    private final Classifier classifier;
    private final MasterEvaluation master;

    public RouterEvaluation(Classifier classifier, MasterEvaluation master) {
        this.classifier = classifier;
        this.master = master;
    }

    public int run(Path tracePath, String caseId, Outcome simulatedOutcome, String routerModel, String masterModel)
            throws IOException, InterruptedException {
        var scenario = master.scenario(caseId);
        if (!scenario.requiredItems().containsAll(List.of("meetingTime", "confirmMeetingNow", "verifyReminder",
                "waitReminderResult", "reportReminderFailure"))) {
            throw new IOException("Router 挡板用例须逐项要求会议时间和四个行动字段");
        }
        try (var trace = new EvaluationTrace(tracePath)) {
            int run = trace.write(caseId, null, "router_run_started", Map.of(
                    "routerModel", routerModel, "masterModel", masterModel, "workerMode", "SIMULATED"));
            int input = trace.write(caseId, run, "router_input", Map.of("text", scenario.routeQuestion()));
            Route route;
            try {
                route = classifier.route(scenario.routeQuestion());
            } catch (IOException error) {
                trace.write(caseId, input, "router_rejected", Map.of("detail", error.getMessage()));
                throw error;
            }
            int routed = trace.write(caseId, input, "router_route", Map.of("route", route));
            if (route != Route.MEETING_RESCHEDULE) {
                trace.write(caseId, routed, "router_rejected", Map.of("detail", "本用例应选择会议改期流程"));
                return 1;
            }
            // 取证仍走原有工具协议。挡板评分通过后才用接收的字段生成会议场景计划。
            var evaluated = master.evaluate(scenario, trace, routed);
            if (!"REVIEW_REQUIRED".equals(evaluated.category())) {
                trace.write(caseId, evaluated.eventId(), "router_rejected", Map.of("detail", evaluated.detail()));
                return 1;
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
                return 1;
            }
            int planned = trace.write(caseId, evaluated.eventId(), "router_plan", plan);
            var workers = new ScriptedWorkers(trace, caseId, planned, simulatedOutcome);
            new MeetingOrchestrator().execute(plan, intent, workers);
            trace.write(caseId, workers.event, "router_run_completed", Map.of("workerMode", "SIMULATED"));
            return 0;
        }
    }

    // 模拟 Worker 只存在于显式挡板入口。回复正文与执行标识分开记录。
    private static final class ScriptedWorkers implements MeetingOrchestrator.Workers {
        private final EvaluationTrace trace;
        private final String caseId;
        private final Outcome simulatedOutcome;
        private int event;

        ScriptedWorkers(EvaluationTrace trace, String caseId, int event, Outcome simulatedOutcome) {
            this.trace = trace;
            this.caseId = caseId;
            this.event = event;
            this.simulatedOutcome = simulatedOutcome;
        }

        @Override
        public void reply(Scope scope, Action action, String text) throws IOException {
            int dispatch = dispatch(scope, action, "CHAT_WORKER", text);
            event = trace.write(caseId, dispatch, "reply_receipt", Map.of("action", action, "simulated", true));
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
