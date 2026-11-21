package neuralcloud.controlplane.evaluation;

import com.google.gson.JsonParser;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Action;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Outcome;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Result;
import neuralcloud.controlplane.orchestration.MeetingOrchestrator.Route;
import neuralcloud.controlplane.orchestration.Orchestrator.Receipt;
import neuralcloud.controlplane.orchestration.Orchestrator.Scope;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RouterEvaluationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void runsFromRoutingThroughEvidenceToTwoReplies() throws Exception {
        var replies = new ArrayDeque<>(List.of(
                call("query_memory", "{\"sources\":[\"FACT\",\"IMPRESSION\"]}"),
                call("submit_results", """
                        {"meetingTime":"周六11时","evidenceLabels":[1,2],"reminderStatus":"UNKNOWN","impression":"无",
                         "actionIntent":"先告知时间，核对并等待提醒回执，收到失败则说明。",
                         "confirmMeetingNow":true,"verifyReminder":true,"waitReminderResult":true,
                         "reportReminderFailure":true}
                        """)));
        var fixture = Path.of(getClass().getResource("/fixtures/master-meeting-evaluation.json").toURI());
        var master = new MasterEvaluation((messages, tools, maxTokens, temperature) -> {
            assertTrue(messages.get(1).get("content").toString().startsWith("当前会话：项目群。事项：会议改期。"));
            assertFalse(messages.get(1).get("content").toString().contains("attempt-without-receipt"));
            return replies.removeFirst();
        }, fixture, "你是 Master。先查证，再判断。");
        var approvedForChat = new ArrayList<String>();
        var evaluation = new RouterEvaluation(text -> {
            assertTrue(text.startsWith("用户问：会议改好了吗？旧提醒取消了吗？"));
            assertFalse(text.contains("事项：会议改期"));
            return Route.MEETING_RESCHEDULE;
        }, master, text -> {
            approvedForChat.add(text);
            return text;
        });
        var trace = temporary.getRoot().toPath().resolve("router.jsonl");
        assertEquals(0, evaluation.run(trace, "attempt-without-receipt", Outcome.CANCELLED, "test-router", "test-master"));
        assertTrue(replies.isEmpty());
        var events = Files.readAllLines(trace).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        var dispatches = events.stream().filter(event -> "worker_dispatch".equals(event.get("type").getAsString())).toList();
        var bodies = dispatches.stream().map(event -> event.getAsJsonObject("data").get("text").getAsString()).toList();
        assertEquals(List.of("会议已改到周六11时。", "请核对本次会议的旧提醒清单，并回报实际结果。",
                "核对结果显示，旧提醒已取消。"), bodies);
        var receipt = events.stream().filter(event -> "reminder_receipt".equals(event.get("type").getAsString()))
                .findFirst().orElseThrow();
        assertEquals(receipt.get("eventId"), dispatches.getLast().get("causedBy"));
        assertTrue(dispatches.getFirst().get("eventId").getAsInt() < receipt.get("eventId").getAsInt());
        assertEquals(List.of(bodies.getFirst(), bodies.getLast()), approvedForChat);
        assertEquals(2, events.stream().filter(event -> "chat_worker_draft".equals(event.get("type").getAsString())).count());
        assertFalse(events.stream().anyMatch(event -> "reply_receipt".equals(event.get("type").getAsString())));
    }

    @Test
    public void refusesAReceiptForAnotherWorkflowAfterTheEarlyReply() {
        var scope = new Scope("项目群", UUID.randomUUID());
        var intent = new MeetingOrchestrator.Intent("周六11时", true, true, true, true);
        var plan = MeetingOrchestrator.plan(scope, intent);
        var replies = new ArrayList<Action>();
        var workers = new MeetingOrchestrator.Workers() {
            @Override
            public void reply(Scope current, Action action, String text) {
                assertEquals(scope, current);
                replies.add(action);
            }

            @Override
            public Receipt<Result> checkReminder(Scope current, String text) {
                assertEquals(List.of(Action.CONFIRM_MEETING), replies);
                return new Receipt<>(new Scope(current.conversation(), UUID.randomUUID()), Action.CHECK_REMINDER.name(),
                        Outcome.CANCELLED);
            }
        };
        assertThrows(IOException.class, () -> new MeetingOrchestrator().execute(plan, intent, workers));
        assertEquals(List.of(Action.CONFIRM_MEETING), replies);
    }

    private static Master.ChatTurn call(String name, String arguments) {
        return new Master.ChatTurn(new Master.Completion("", "tool_calls", false),
                List.of(new Master.ToolCall(name, name, arguments)));
    }
}
