package neuralcloud.controlplane.orchestration;

import neuralcloud.controlplane.router.Router.Action;
import neuralcloud.controlplane.router.Router.Plan;
import neuralcloud.controlplane.router.Router.Scope;

import java.io.IOException;

public final class MeetingOrchestrator {
    public enum Outcome { PRESENT, CANCELLED, FAILED }

    public record ReminderReceipt(Scope scope, Action action, Outcome outcome) {}

    public interface Workers {
        void reply(Scope scope, Action action, String text) throws IOException;

        ReminderReceipt checkReminder(Scope scope, String text) throws IOException;
    }

    public void execute(Plan plan, Workers workers) throws IOException {
        ReminderReceipt receipt = null;
        for (var step : plan.steps()) {
            switch (step.action()) {
                case CONFIRM_MEETING -> workers.reply(plan.scope(), step.action(), step.text());
                case CHECK_REMINDER -> {
                    // 首版在这个边界等待实际核对结果，调用返回前不派发提醒状态回复。
                    receipt = workers.checkReminder(plan.scope(), step.text());
                    if (receipt == null || !plan.scope().equals(receipt.scope())
                            || receipt.action() != step.action() || receipt.outcome() == null) {
                        throw new IOException("提醒核对回执缺失结果或与本事项的核对步骤不匹配");
                    }
                }
                case REPORT_REMINDER -> {
                    if (receipt == null || receipt.action() != step.dependsOn()) {
                        throw new IOException("提醒状态回复缺少对应的核对回执");
                    }
                    if (receipt.outcome() == Outcome.FAILED ? plan.reportReminderFailure() : plan.reportReminderResult()) {
                        String text = switch (receipt.outcome()) {
                            case PRESENT -> "核对结果显示，旧提醒仍然存在。";
                            case CANCELLED -> "核对结果显示，旧提醒已取消。";
                            case FAILED -> "这次未能核对旧提醒，暂时无法确认提醒状态。";
                        };
                        workers.reply(plan.scope(), step.action(), text);
                    }
                }
            }
        }
    }
}
