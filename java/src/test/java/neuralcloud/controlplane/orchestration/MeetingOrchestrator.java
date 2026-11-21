package neuralcloud.controlplane.orchestration;

import neuralcloud.controlplane.orchestration.Orchestrator.Plan;
import neuralcloud.controlplane.orchestration.Orchestrator.Receipt;
import neuralcloud.controlplane.orchestration.Orchestrator.Scope;
import neuralcloud.controlplane.orchestration.Orchestrator.Step;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// 单会话联调场景负责会议规则和回复文案，正式编排器执行该场景提交的计划。
public final class MeetingOrchestrator {
    public enum Action {
        CONFIRM_MEETING("CHAT_WORKER"), CHECK_REMINDER("TASK_WORKER"), REPORT_REMINDER("CHAT_WORKER");

        private final String worker;

        Action(String worker) {
            this.worker = worker;
        }

        private Step step(String text, Action... dependencies) {
            return new Step(name(), worker, text, List.of(dependencies).stream().map(Action::name).toList());
        }
    }

    public sealed interface Result permits Outcome, Reply {}

    public enum Outcome implements Result { PRESENT, CANCELLED, FAILED }

    private enum Reply implements Result { COMPLETED, SKIPPED }

    public record Intent(String meetingTime, boolean confirmMeetingNow, boolean verifyReminder,
                         boolean waitReminderResult, boolean reportReminderFailure) {}

    public interface Workers {
        void reply(Scope scope, Action action, String text) throws IOException;

        Receipt<Result> checkReminder(Scope scope, String text) throws IOException;
    }

    public static Plan plan(Scope scope, Intent intent) {
        if ((intent.confirmMeetingNow() && "UNKNOWN".equals(intent.meetingTime()))
                || ((intent.waitReminderResult() || intent.reportReminderFailure()) && !intent.verifyReminder())) {
            throw new IllegalArgumentException("会议行动契约要求已核实的会议时间或可取得的提醒核对回执");
        }
        var steps = new ArrayList<Step>();
        // 已核实的会议时间有独立依据，等待提醒核对时仍可先告知。
        if (intent.confirmMeetingNow()) {
            steps.add(Action.CONFIRM_MEETING.step("会议已改到" + intent.meetingTime() + "。"));
        }
        if (intent.verifyReminder()) {
            steps.add(Action.CHECK_REMINDER.step("请核对本次会议的旧提醒清单，并回报实际结果。"));
        }
        if (intent.waitReminderResult() || intent.reportReminderFailure()) {
            steps.add(Action.REPORT_REMINDER.step("请根据核对回执告知旧提醒状态。", Action.CHECK_REMINDER));
        }
        return new Plan(scope, steps);
    }

    public void execute(Plan plan, Intent intent, Workers workers) throws IOException {
        new Orchestrator().<Result>execute(plan, (scope, step, dependencies) -> {
            Action action = Action.valueOf(step.id());
            return switch (action) {
                case CONFIRM_MEETING -> reply(workers, scope, action, step.text());
                case CHECK_REMINDER -> workers.checkReminder(scope, step.text());
                case REPORT_REMINDER -> {
                    Outcome outcome = (Outcome) dependencies.get(Action.CHECK_REMINDER.name()).result();
                    yield reportReminder(workers, scope, intent, outcome);
                }
            };
        });
    }

    private static Receipt<Result> reportReminder(Workers workers, Scope scope, Intent intent, Outcome outcome)
            throws IOException {
        var action = Action.REPORT_REMINDER;
        boolean shouldReply = outcome == Outcome.FAILED ? intent.reportReminderFailure() : intent.waitReminderResult();
        if (!shouldReply) {
            return new Receipt<>(scope, action.name(), Reply.SKIPPED);
        }
        String text = switch (outcome) {
            case PRESENT -> "核对结果显示，旧提醒仍然存在。";
            case CANCELLED -> "核对结果显示，旧提醒已取消。";
            case FAILED -> "这次未能核对旧提醒，暂时无法确认提醒状态。";
        };
        return reply(workers, scope, action, text);
    }

    private static Receipt<Result> reply(Workers workers, Scope scope, Action action, String text) throws IOException {
        workers.reply(scope, action, text);
        return new Receipt<>(scope, action.name(), Reply.COMPLETED);
    }
}
