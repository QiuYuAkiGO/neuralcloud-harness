package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpServer;
import neuralcloud.controlplane.console.ConsoleServer;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.worker.ChatWorker;
import neuralcloud.controlplane.worker.ServerStatus;
import neuralcloud.controlplane.worker.TaskWorker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatCompactionSemanticsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final String CHAIN = "semantics";
    private static final String DAYS = "周五还是周六，等我定。";
    private static final String CHOICE = "用户在周五和周六之间选择，目前还没定。";
    private static final String DRAFT = "还欠一份清单";
    private static final String ACTION = "请检查这台服务器的运行状态。";
    private static final String PENDING = "还要替用户检查这台服务器的运行状态。";
    private static final String PROMISE = "我去检查这台服务器。";
    private static final String INTRO = "嗯，我刚看了下这台服务器。";
    private static final String REPLY_PLAN = "{\"kind\":\"REPLY\",\"query\":\"\"}";

    /** 公开配置降低预算。每轮用三个完整的旧输入越过水位，保留充足的最近原文。 */
    private ChatCompactionFixture fixture() throws Exception {
        return new ChatCompactionFixture(folder.newFolder().toPath(), ChatWorker.ApiFormat.GEMINI,
                "contextCompression=ROLLING\nhistoryBudgetUnits=4096\n");
    }

    private static void history(ChatCompactionFixture f, String marker, int units) throws IOException {
        for (int i = 1; i <= 3; i++) {
            f.inbound(CHAIN, marker + i + "。记" + "事".repeat(units));
        }
    }

    /** 模型只引用自己在本次请求见到的编号；缺失证据用越界编号，禁止以预期答案代替输入。 */
    private static int source(ChatCompactionFixture.Call call, String text) {
        return call.entries().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> row.get("text").getAsString().contains(text))
                .mapToInt(row -> row.get("index").getAsInt()).findFirst().orElse(0);
    }

    private static String item(String kind, String text, int index) {
        return JSON.toJson(Map.of("items", List.of(Map.of("kind", kind, "text", text, "sources", List.of(index))),
                "closed", List.of()));
    }

    private static boolean saw(ChatCompactionFixture.Call call, String text) {
        return call.messages().stream().anyMatch(message -> message.text().contains(text));
    }

    private static List<String> statuses(ChatCompactionFixture f) {
        return f.audit.events(CHAIN).stream().filter(event -> "chat_context_compaction".equals(event.type()))
                .map(event -> event.data().getAsJsonObject().get("status").getAsString()).toList();
    }

    /** 第一次从用户原话提炼待定日期，第二次只能由上版未决延续，实际答复按收到的资料作答。 */
    @Test public void twoCompactionsRetainBothPossibleDatesAndTheUnresolvedChoice() throws Exception {
        try (var f = fixture()) {
            f.inbound(CHAIN, DAYS);
            history(f, "第一段", 1080);
            f.summary = call -> item("OPEN_POINT", CHOICE, source(call, DAYS));
            f.turn(CHAIN, "先聊点别的。");
            assertEquals(List.of("COMMITTED"), statuses(f));

            history(f, "第二段", 770);
            f.summary = call -> item("OPEN_POINT", CHOICE, source(call, CHOICE));
            f.reply = call -> saw(call, CHOICE) ? "你在周五和周六之间选，还没定。" : "我不知道。";
            assertEquals("你在周五和周六之间选，还没定。", f.turn(CHAIN, "我在选哪两天？"));
            assertEquals(List.of("COMMITTED", "COMMITTED"), statuses(f));
            var summaries = f.calls("submit_chat_summary");
            assertEquals(2, summaries.size());
            assertTrue(source(summaries.getFirst(), DAYS) > 0);
            assertEquals(0, source(summaries.getLast(), DAYS));
            assertTrue(source(summaries.getLast(), CHOICE) > 0);
            assertTrue(saw(f.calls("plan_turn").getLast(), CHOICE));
        }
    }

    /** 让模型遇到未送达草稿就留待办。随后的网页发言正常交付，两个模型边界仍应排除草稿。 */
    @Test public void undeliveredDraftDoesNotBecomeAReminder() throws Exception {
        try (var f = fixture()) {
            String topic = "讨论周末的安排。";
            var exchange = f.entry(Optional.empty());
            int user = f.inbound(CHAIN, topic);
            var trace = Trace.forChain(f.audit, "console", CHAIN, user);
            f.reply = call -> "我" + DRAFT + "。";
            var draft = exchange.send(topic, trace);
            f.audit.write(CHAIN, draft.causedBy(), "chat-worker", draft.type(), draft.data());
            f.reply = call -> "周末安排可以晚点再聊。";
            f.turn(CHAIN, "先到这里。");
            history(f, "另一个话题", 1080);
            f.summary = call -> source(call, DRAFT) > 0
                    ? item("OPEN_POINT", DRAFT, source(call, DRAFT))
                    : item("TOPIC", "此前聊过周末。", source(call, topic));
            f.reply = call -> saw(call, DRAFT) ? "还有一份清单要补发。" : "没有要补发的事项。";
            assertEquals("没有要补发的事项。", f.turn(CHAIN, "我有要补发的东西吗？"));
            assertEquals(List.of("COMMITTED"), statuses(f));
            assertEquals(0, source(f.calls("submit_chat_summary").getFirst(), DRAFT));
            assertFalse(saw(f.calls("plan_turn").getLast(), DRAFT));
            assertFalse(saw(f.calls("reply").getLast(), DRAFT));
        }
    }

    @Test public void receiptForTheOriginalActionClosesThePendingPoint() throws Exception {
        taskClosure(true);
    }

    @Test public void successForAnotherRequestCannotCloseTheOriginalPoint() throws Exception {
        taskClosure(false);
    }

    /** 通过正式任务入口执行一次 read_server_status。若是原请求，读数留到首次提要提交后才返回。 */
    private void taskClosure(boolean sameRequest) throws Exception {
        var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/task", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = """
                        {"model":"task","choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                        {"id":"read","type":"function","function":{"name":"read_server_status","arguments":"{}"}}]}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        service.start();
        var reading = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var f = fixture(); var http = HttpClient.newHttpClient();
             var runner = Executors.newVirtualThreadPerTaskExecutor()) {
            int original = f.inbound(CHAIN, ACTION);
            if (!sameRequest) establishOpen(f);
            String requestText = sameRequest ? ACTION : "另起一项，请现在读取服务器状态。";
            int request = sameRequest ? original : f.inbound(CHAIN, requestText);
            URI endpoint = URI.create("http://127.0.0.1:" + service.getAddress().getPort() + "/task");
            var task = new TaskWorker(http, endpoint, "task", "fixture", Duration.ofSeconds(3), () -> {
                reading.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new IOException("采样未获释放");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                return new ServerStatus("2026-11-24T00:00:00Z", 7200, 0.5, 2L << 30, 4L << 30, 8L << 30, 16L << 30);
            });
            f.route = call -> "ACTION_REQUEST";
            f.plan = call -> "{\"kind\":\"ACTION\",\"query\":\"\"}";
            f.statusIntro = call -> INTRO;
            var action = ConsoleMain.conversation(f.chat, f.router, Optional.of(task), Optional.empty());
            var delivery = runner.submit(() -> {
                var trace = Trace.forChain(f.audit, "console", CHAIN, request);
                var out = action.send(requestText, trace);
                deliver(f, out);
                return (String) out.data().get("text");
            });
            try {
                assertTrue("正式任务没有到达状态读取边界", reading.await(5, TimeUnit.SECONDS));
                f.route = call -> "CONVERSATION";
                f.plan = call -> REPLY_PLAN;
                if (sameRequest) establishOpen(f);
            } finally {
                finish.countDown();
            }
            assertTrue(delivery.get(5, TimeUnit.SECONDS).contains(INTRO));
            attemptClosure(f, INTRO, sameRequest);
        } finally {
            finish.countDown();
            service.stop(0);
        }
    }

    /** 用同一条请求生成并送达承诺，只提供渠道回执。压缩模型提出结项时须退回上一份未决。 */
    @Test public void deliveringAPromiseCannotCloseTheAction() throws Exception {
        try (var f = fixture()) {
            int request = f.inbound(CHAIN, ACTION);
            f.reply = call -> PROMISE;
            var promise = f.entry(Optional.empty()).send(ACTION, Trace.forChain(f.audit, "console", CHAIN, request));
            f.reply = call -> "收到。";
            establishOpen(f);
            deliver(f, promise);
            attemptClosure(f, PROMISE, false);
        }
    }

    @Test public void explicitCancellationByTheRequesterClosesThePendingPoint() throws Exception {
        userDecision("取消这个请求，不用再查这台服务器的运行状态了。", true);
    }

    @Test public void askingNotToCancelCannotCloseThePendingPoint() throws Exception {
        userDecision("不要取消这个请求，我仍然需要这台服务器的运行状态。", false);
    }

    /** 先保留原请求为未决。模型在两例都引用当事人的新原话结项，宿主按这条话的意思决定是否接受。 */
    private void userDecision(String words, boolean cancelled) throws Exception {
        try (var f = fixture()) {
            f.inbound(CHAIN, ACTION);
            establishOpen(f);
            f.inbound(CHAIN, words);
            attemptClosure(f, words, cancelled);
        }
    }

    /** 渠道先发出本轮答复，再记载成功写出。回执关联的业务结果由正式处理器写下。 */
    private static void deliver(ChatCompactionFixture f, ConsoleServer.Reply out) throws IOException {
        int reply = f.audit.write(CHAIN, out.causedBy(), "chat-worker", out.type(), out.data());
        f.audit.write(CHAIN, reply, "console", "console_response_written", Map.of());
    }

    private static void establishOpen(ChatCompactionFixture f) throws Exception {
        history(f, "准备讨论", 1080);
        f.summary = call -> item("OPEN_POINT", PENDING, source(call, ACTION));
        f.turn(CHAIN, "暂时说点别的。");
        assertEquals(List.of("COMMITTED"), statuses(f));
        assertTrue(saw(f.calls("plan_turn").getLast(), PENDING));
    }

    /** 两种候选使用相同的结项协议；只改变本批新证据。保留另一话题，区分已结项与摘要整体丢失。 */
    private static void attemptClosure(ChatCompactionFixture f, String evidence, boolean accepted) throws Exception {
        String marker = "后续闲聊";
        history(f, marker, 770);
        f.summary = call -> JSON.toJson(Map.of("items", List.of(Map.of("kind", "TOPIC", "text", "开始聊后续安排。",
                        "sources", List.of(source(call, marker)))),
                "closed", List.of(Map.of("previous", source(call, PENDING), "evidence", source(call, evidence)))));
        f.turn(CHAIN, "记得我们还要做什么吗？");
        var sent = f.calls("submit_chat_summary").getLast();
        assertTrue("新证据须实际进入压缩请求", source(sent, evidence) > 0);
        assertEquals(List.of("COMMITTED", accepted ? "COMMITTED" : "REJECTED"), statuses(f));
        var plan = f.calls("plan_turn").getLast();
        assertEquals(!accepted, saw(plan, PENDING));
        assertEquals(accepted, saw(plan, "开始聊后续安排。"));
        if (accepted) {
            f.turn(CHAIN, "继续聊后续安排。");
            assertFalse(saw(f.calls("plan_turn").getLast(), PENDING));
        }
    }
}
