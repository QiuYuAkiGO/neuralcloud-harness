package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import neuralcloud.controlplane.app.ChatCompactionFixture.Call;
import neuralcloud.controlplane.app.ChatCompactionFixture.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static neuralcloud.controlplane.worker.ChatWorker.ApiFormat.OPENAI_CHAT;
import static org.junit.Assert.*;

/** 在网页正式会话入口触发整理。只从供应商请求和审计事件观察冻结、提交及恢复结果。 */
public class ChatCompactionRecoveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final Gson JSON = new Gson();
    private static final String CHAIN = "河堤会话";
    private static final String ROLLING = "contextCompression=ROLLING\n";
    private static final int BUDGET = 48_000;
    private static final String OLD_MARK = "旧批次的杨梅桥通行时间";
    private static final String OLD = OLD_MARK + "前".repeat(19_000);
    private static final String TAIL = "保留为原文的堤岸路况" + "近".repeat(18_000);
    private static final String SUMMARY = "用户在讨论杨梅桥开放安排。";
    private static final String NEXT_SUMMARY = "用户又提到了堤岸路况。";
    private static final String FROZEN_QUESTION = "请整理这一轮之前的安排。";
    private static final String LATER = "冻结后第二位发言者补充了青石渡口。";

    /** 甲轮到达模型后才加入乙消息；另启 JVM，确保下一轮只能从磁盘恢复。 */
    @Test public void concurrentInboundAppearsOnceAfterRestartAndNeverInTheFrozenTurn() throws Exception {
        Path directory = folder.newFolder("审计").toPath();
        try (var f = rolling(directory)) {
            seed(f);
            whileSummaryIsPending(f, () -> f.inbound(CHAIN, LATER));
            assertEquals("COMMITTED", diagnostic(f).get("status").getAsString());
            assertTrue(only(f, "submit_chat_summary").body().toString().contains(OLD_MARK));
            assertTrue(f.calls.stream().noneMatch(call -> call.body().toString().contains(LATER)));
        }
        Path output = folder.getRoot().toPath().resolve("重启验证.log");
        var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp",
                System.getProperty("java.class.path"), getClass().getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue("重启验证未返回", process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(output), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    /** 通过正式入口续聊。新 JVM 没有上一轮的内存状态，只能使用给定的审计目录。 */
    public static void main(String[] args) throws Exception {
        try (var f = rolling(Path.of(args[0]))) {
            f.turn(CHAIN, "重启后接着聊。");
            var history = history(only(f, "plan_turn"), "重启后接着聊。");
            assertTrue(history.stream().anyMatch(message -> message.text().contains(SUMMARY)));
            assertEquals(1, history.stream().filter(message -> message.equals(new Message("user", LATER))).count());
            assertTrue(f.calls("submit_chat_summary").isEmpty());
        }
    }

    /** 已冻结的候选在重置之后才返回；本轮和重启后的普通模型都只能接触重置后的历史。 */
    @Test public void concurrentResetRejectsTheFrozenCandidateEvenAfterRestart() throws Exception {
        Path directory = folder.getRoot().toPath();
        try (var f = rolling(directory)) {
            seed(f);
            whileSummaryIsPending(f, () -> {
                int inbound = f.inbound(CHAIN, "这条依据需要更正。");
                return f.audit.write(CHAIN, inbound, "memory", "memory_context_reset",
                        Map.of("materials", List.of("桥面已重新开放。"), "input_id", f.audit.runId() + ":" + inbound + "-refresh"));
            });
            assertEquals("REJECTED", diagnostic(f).get("status").getAsString());
            assertTrue(f.calls.stream().filter(call -> List.of("plan_turn", "reply").contains(call.name()))
                    .noneMatch(ChatCompactionRecoveryTest::containsOldHistory));
        }
        try (var f = rolling(directory)) {
            f.turn(CHAIN, "重启后的新问题。");
            assertTrue(f.calls("submit_chat_summary").isEmpty());
            only(f, "plan_turn");
            assertTrue(f.calls.stream().noneMatch(ChatCompactionRecoveryTest::containsOldHistory));
        }
    }

    private static boolean containsOldHistory(Call call) {
        String body = call.body().toString();
        return body.contains(OLD_MARK) || body.contains(TAIL) || body.contains(SUMMARY);
    }

    /** 唯一发言已越过水位但能整体装入硬上限，普通规划仍可读到完整首尾。 */
    @Test public void keepsTheOnly37000UnitMessageWholeWithoutSummarizing() throws Exception {
        String single = "开" + "记".repeat(36_997) + "⚓尾";
        assertEquals(37_000, single.length());
        try (var f = rolling(folder.getRoot().toPath())) {
            f.inbound(CHAIN, single);
            f.turn(CHAIN, "这一条讲了什么？");
            assertTrue(f.calls("submit_chat_summary").isEmpty());
            assertEquals(List.of(new Message("user", single)), history(only(f, "plan_turn"), "这一条讲了什么？"));
        }
    }

    /** 先以等长 BMP 正文实测切点，再令表情跨过该切点，重启后逐轮拼回完整正文。 */
    @Test(timeout = 60_000) public void resumesAnOversizedMessageAtACompleteCodePointAfterRestart() throws Exception {
        var numbered = new StringBuilder();
        for (int index = 0; numbered.length() < 65_000; index++) numbered.append("堤岸路标").append(index).append('。');
        String probe = numbered.toString();
        int boundary;
        try (var f = rolling(folder.newFolder("测量切点").toPath())) {
            f.inbound(CHAIN, probe);
            f.inbound(CHAIN, TAIL);
            f.turn(CHAIN, "首批整理。");
            boundary = singleFragment(f).length();
            assertTrue("超长原文应有后续片段", boundary > 0 && boundary < probe.length());
        }
        String original = probe.substring(0, boundary - 1) + "🧭" + probe.substring(boundary + 1);
        var recovered = new StringBuilder();
        Path directory = folder.newFolder("恢复分片").toPath();
        try (var f = rolling(directory)) {
            f.inbound(CHAIN, original);
            f.inbound(CHAIN, TAIL);
            readNextFragment(f, recovered, original, "首批整理。");
            assertTrue("重启发生在消息仍有未覆盖原文时", recovered.length() < original.length());
            assertEquals(recovered.length(), diagnostic(f).get("offset").getAsInt());
        }
        try (var f = rolling(directory)) {
            while (recovered.length() < original.length()) {
                readNextFragment(f, recovered, original, "继续整理分片。");
            }
        }
        assertEquals(original, recovered.toString());
    }

    /** 基线与下一次合法候选取自不同旧前缀，模型和诊断都应看到同一次提交的结果。 */
    @Test public void validCandidateAdvancesRevisionAndCoverageTogether() throws Exception {
        try (var f = rolling(folder.getRoot().toPath())) {
            JsonObject baseline = commitBaseline(f);
            String newest = "新的通行通知" + "新".repeat(19_000);
            f.inbound(CHAIN, newest);
            f.summary = call -> topic(NEXT_SUMMARY, firstUser(call).get("index").getAsInt());
            f.turn(CHAIN, "再整理一次。");
            assertEquals(TAIL, firstUser(only(f, "submit_chat_summary")).get("text").getAsString());
            var next = diagnostic(f);
            assertEquals("COMMITTED", next.get("status").getAsString());
            assertTrue(next.get("revision").getAsLong() > baseline.get("revision").getAsLong());
            assertNotEquals(baseline.get("covered").getAsString(), next.get("covered").getAsString());
            var data = history(only(f, "plan_turn"), "再整理一次。");
            assertTrue(data.stream().anyMatch(message -> message.text().contains(NEXT_SUMMARY)));
            assertTrue(data.contains(new Message("user", newest)));
            assertFalse(data.contains(new Message("user", TAIL)));
        }
    }

    /** 从同一提交先后返回越界引用和真实 HTTP 超时；每轮回落到连续整条尾部，重试从原覆盖点开始。 */
    @Test public void rejectedAndTimedOutCandidatesKeepTheSameCursorAndExposeTheGap() throws Exception {
        try (var f = new ChatCompactionFixture(folder.getRoot().toPath(), OPENAI_CHAT, ROLLING, 2)) {
            var baseline = commitBaseline(f);
            var recent = List.of(block("甲"), block("乙"), block("丙"), block("丁"));
            for (String text : recent) f.inbound(CHAIN, text);
            for (String status : List.of("REJECTED", "FAILED")) {
                var release = new CountDownLatch(1);
                f.summary = call -> {
                    if ("REJECTED".equals(status)) return topic("无来源的候选", call.entries().size() + 1);
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("超时轮未返回");
                    return topic("迟到的候选", 1);
                };
                String question = "检查本轮缺口 " + status;
                try {
                    f.turn(CHAIN, question);
                } finally {
                    release.countDown();
                }
                assertEquals(TAIL, firstUser(only(f, "submit_chat_summary")).get("text").getAsString());
                var failed = diagnostic(f);
                assertEquals(status, failed.get("status").getAsString());
                assertEquals(baseline.get("revision"), failed.get("revision"));
                assertEquals(baseline.get("covered"), failed.get("covered"));
                assertEquals(baseline.get("offset"), failed.get("offset"));
                assertTrue(failed.get("gapUnits").getAsInt() > 0);
                var data = history(only(f, "plan_turn"), question);
                assertTrue(data.stream().anyMatch(message -> message.text().contains(SUMMARY)));
                assertTrue(data.stream().anyMatch(message -> message.text().contains("未展开")));
                assertTrue(data.stream().mapToInt(message -> message.text().length()).sum() <= BUDGET);
                assertEquals(recent.subList(1, 4), data.stream().map(Message::text)
                        .filter(text -> text.contains("测线")).toList());
                assertFalse(data.stream().anyMatch(message -> message.text().contains("无来源的候选")
                        || message.text().contains("迟到的候选")));
                f.calls.clear();
            }
            f.summary = call -> topic(NEXT_SUMMARY, firstUser(call).get("index").getAsInt());
            f.turn(CHAIN, "继续重试。");
            assertEquals(TAIL, firstUser(only(f, "submit_chat_summary")).get("text").getAsString());
            assertEquals("COMMITTED", diagnostic(f).get("status").getAsString());
        }
    }

    /** 甲轮到达摘要供应商时放行写入任务，写入必须在摘要返回前独立记账。 */
    private static void whileSummaryIsPending(ChatCompactionFixture f, Callable<Integer> concurrentWrite) throws Exception {
        var frozen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        f.summary = call -> {
            frozen.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("未收到冻结期间的审计回执");
            return topic(SUMMARY, firstUser(call).get("index").getAsInt());
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var turn = pool.submit(() -> f.turn(CHAIN, FROZEN_QUESTION));
            try {
                assertTrue("甲轮未发起摘要调用", frozen.await(4, TimeUnit.SECONDS));
                pool.submit(concurrentWrite).get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            turn.get(8, TimeUnit.SECONDS);
        }
    }

    /** 整条近期对话仅作为普通模型历史，分片只从每轮唯一的摘要请求中提取。 */
    private static void readNextFragment(ChatCompactionFixture f, StringBuilder collected, String original,
                                         String question) throws Exception {
        f.calls.clear();
        f.summary = call -> topic("来自堤岸路标的分段提要。", firstUser(call).get("index").getAsInt());
        f.turn(CHAIN, question);
        String fragment = singleFragment(f);
        assertFalse(fragment.isEmpty());
        assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
        assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
        assertTrue("必须从上一次已确认的 UTF-16 偏移继续", original.startsWith(fragment, collected.length()));
        collected.append(fragment);
        assertEquals("COMMITTED", diagnostic(f).get("status").getAsString());
        var ordinary = history(only(f, "plan_turn"), question);
        assertTrue(ordinary.contains(new Message("user", TAIL)));
        assertFalse(ordinary.contains(new Message("user", fragment)));
    }

    private static String singleFragment(ChatCompactionFixture f) {
        var users = users(only(f, "submit_chat_summary"));
        assertEquals("近期原文之前只有一条超长消息", 1, users.size());
        return users.getFirst().get("text").getAsString();
    }

    /** 相同原文、同一合法候选建立每组案例的起点；本次提交后的近期发言留给下一批。 */
    private static JsonObject commitBaseline(ChatCompactionFixture f) throws Exception {
        seed(f);
        f.summary = call -> topic(SUMMARY, firstUser(call).get("index").getAsInt());
        f.turn(CHAIN, "准备一次整理。");
        var baseline = diagnostic(f);
        assertEquals("COMMITTED", baseline.get("status").getAsString());
        assertFalse(baseline.get("covered").getAsString().isBlank());
        assertTrue(history(only(f, "plan_turn"), "准备一次整理。").stream()
                .anyMatch(message -> message.text().contains(SUMMARY)));
        f.calls.clear();
        return baseline;
    }

    private static void seed(ChatCompactionFixture f) throws IOException {
        f.inbound(CHAIN, OLD);
        f.inbound(CHAIN, TAIL);
    }

    private static String block(String name) {
        return "测线" + name + name.repeat(15_000) + name + "测线结束";
    }

    private static ChatCompactionFixture rolling(Path path) throws IOException {
        return new ChatCompactionFixture(path, OPENAI_CHAT, ROLLING);
    }

    private static List<JsonObject> users(Call call) {
        return call.entries().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(entry -> "USER".equals(entry.get("kind").getAsString())).toList();
    }

    private static JsonObject firstUser(Call call) {
        return users(call).getFirst();
    }

    private static String topic(String text, int source) {
        return JSON.toJson(Map.of("items", List.of(Map.of("kind", "TOPIC", "text", text, "sources", List.of(source))),
                "closed", List.of()));
    }

    private static Call only(ChatCompactionFixture f, String operation) {
        var calls = f.calls(operation);
        assertEquals("本轮的 " + operation + " 调用数", 1, calls.size());
        return calls.getFirst();
    }

    /** 直接从实际协议消息取历史段，把固定系统提示和本轮问题排除在历史预算之外。 */
    private static List<Message> history(Call plan, String question) {
        var data = plan.messages().stream().filter(message -> !message.role().equals("system")).toList();
        assertEquals(new Message("user", question), data.getLast());
        return data.subList(0, data.size() - 1);
    }

    private static JsonObject diagnostic(ChatCompactionFixture f) {
        return f.audit.events(CHAIN).stream().filter(event -> event.type().equals("chat_context_compaction"))
                .toList().getLast().data().getAsJsonObject();
    }
}
