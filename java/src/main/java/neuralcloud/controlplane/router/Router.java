package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import neuralcloud.controlplane.master.Master;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Router {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String ROUTE_TOOL = "select_route";
    private static final List<Map<String, Object>> TOOLS = List.of(Map.of(
            "type", "function",
            "function", Map.of("name", ROUTE_TOOL, "description", "选择当前事项的业务流程",
                    "parameters", Map.of("type", "object", "additionalProperties", false,
                            "required", List.of("route"), "properties", Map.of("route", Map.of(
                                    "type", "string", "enum", List.of("MEETING_RESCHEDULE", "UNSUPPORTED")))))));

    public enum Route { MEETING_RESCHEDULE, UNSUPPORTED }
    public enum Action { CONFIRM_MEETING, CHECK_REMINDER, REPORT_REMINDER }

    public record Scope(String conversation, UUID workflowId) {}
    public record Intent(String meetingTime, boolean confirmMeetingNow, boolean verifyReminder,
                         boolean waitReminderResult, boolean reportReminderFailure) {}
    public record Step(Action action, String text, Action dependsOn) {}
    public record Plan(Scope scope, List<Step> steps, boolean reportReminderResult, boolean reportReminderFailure) {
        public Plan {
            steps = List.copyOf(steps);
        }
    }

    private final Master inference;
    private final String rolePrompt;

    public Router(Master inference, String rolePrompt) {
        this.inference = inference;
        this.rolePrompt = rolePrompt;
    }

    public Route route(String report) throws IOException, InterruptedException {
        // 0.8B 只选择流程。会话标识和执行数据由 Java 保留，正文只含事项材料。
        var response = inference.chat(List.of(
                Map.of("role", "system", "content", rolePrompt),
                Map.of("role", "user", "content", report)), TOOLS, 96, 0);
        var calls = response.calls();
        if (calls.size() == 1 && ROUTE_TOOL.equals(calls.getFirst().name())) {
            try {
                var input = JSON.fromJson(calls.getFirst().arguments(), JsonObject.class);
                if (input != null && input.size() == 1 && input.get("route") instanceof JsonPrimitive value
                        && value.isString()) {
                    return Route.valueOf(value.getAsString());
                }
            } catch (JsonParseException | IllegalArgumentException error) {
                throw new IOException("Router 的 select_route.route 不符合流程枚举契约", error);
            }
        }
        throw new IOException("Router 须通过一次 select_route 调用提交唯一的 route 字段");
    }

    public static Plan plan(Scope scope, Intent intent) {
        if ((intent.confirmMeetingNow() && "UNKNOWN".equals(intent.meetingTime()))
                || ((intent.waitReminderResult() || intent.reportReminderFailure()) && !intent.verifyReminder())) {
            throw new IllegalArgumentException("Router 行动契约要求已核实的会议时间或可取得的提醒核对回执");
        }
        var steps = new ArrayList<Step>();
        // 已核实的会议时间有独立依据，等待提醒核对时仍可先告知。
        if (intent.confirmMeetingNow()) {
            steps.add(new Step(Action.CONFIRM_MEETING, "会议已改到" + intent.meetingTime() + "。", null));
        }
        if (intent.verifyReminder()) {
            steps.add(new Step(Action.CHECK_REMINDER, "请核对本次会议的旧提醒清单，并回报实际结果。", null));
        }
        if (intent.waitReminderResult() || intent.reportReminderFailure()) {
            steps.add(new Step(Action.REPORT_REMINDER, "请根据核对回执告知旧提醒状态。", Action.CHECK_REMINDER));
        }
        return new Plan(scope, steps, intent.waitReminderResult(), intent.reportReminderFailure());
    }
}
