package neuralcloud.controlplane.app;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import neuralcloud.controlplane.app.ChatCompactionFixture.Message;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static neuralcloud.controlplane.worker.ChatWorker.ApiFormat.OPENAI_CHAT;
import static org.junit.Assert.*;

/** 经实际模型请求核对档位切换和迟到回执，预置历史只通过渠道审计的正式写入口。 */
public class ChatCompactionProfileAndDeliveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final String CHAIN = "profile-and-delivery";
    private static final int SMALL = 4_096;
    private static final int LARGE = 48_000;
    private static final String QUESTION = "继续说此前的步道安排。";
    private static final String OLD_SOURCE = "第一版步道公告暂定周五开通。";
    private static final String OLD_ANSWER = "那我把旧公告的时间记作周五午后。";

    /** 同一档位重开，最近原文与既有带标签提要均在预算内，原封不动复用已提交条目。 */
    @Test public void sameProfileReusesThePersistedRenderedSummary() throws Exception {
        Path dir = folder.getRoot().toPath();
        String tail = recent(1_200);
        String saved = commitLargeProfile(dir, SMALL / 12, tail);
        try (var f = fixture(dir, LARGE)) {
            f.turn(CHAIN, QUESTION);
            assertTrue(f.calls("submit_chat_summary").isEmpty());
            for (String name : List.of("plan_turn", "reply")) {
                var history = history(f, name);
                assertTrue(history.contains(new Message("user", saved)));
                assertTrue(history.contains(new Message("user", tail)));
            }
        }
    }

    /** 正文恰好占满小档提要额度，可见标签才使已存提要越界；另用超长旧提要覆盖换档失败恢复。 */
    @Test public void smallerProfileEnforcesItsRenderedSummaryAndWholeHistoryLimits() throws Exception {
        for (int textUnits : List.of(SMALL / 12, 3_500)) {
            Path dir = folder.newFolder().toPath();
            String tail = recent(textUnits == SMALL / 12 ? 1_200 : 3_000);
            String saved = commitLargeProfile(dir, textUnits, tail);
            assertTrue("带标签的旧提要应超过新档提要限额", saved.length() > SMALL / 12);
            try (var f = fixture(dir, SMALL)) {
                // 若宿主决定先重新整理，供应商返回无效引用。读取基线和失败回落都必须遵守当前档位。
                f.summary = call -> topic("未核验的新候选。", call.entries().size() + 1);
                f.turn(CHAIN, QUESTION);
                assertTrue(f.calls("submit_chat_summary").size() <= 1);
                for (String name : List.of("plan_turn", "reply")) {
                    var history = history(f, name);
                    int summaryUnits = history.stream().filter(message -> message.text().contains("同会话历史提要"))
                            .mapToInt(message -> message.text().length()).sum();
                    assertTrue(name + " 的实际提要占 " + summaryUnits + " 单元", summaryUnits <= SMALL / 12);
                    assertTrue(name + " 历史超过小档硬预算", history.stream().mapToInt(m -> m.text().length()).sum() <= SMALL);
                    assertTrue(name + " 应保留能放下的最近整条发言", history.contains(new Message("user", tail)));
                }
            }
        }
    }

    private enum ResetTiming { NONE, BEFORE_DRAFT_RECORD, BEFORE_RECEIPT }

    /** 分别经旧档和启用投影的正式 Trace 产生渠道回执，新一轮应可读取这次已确认送达的原文。 */
    @Test public void confirmedRepliesRemainVisibleForLegacyAndRollingReceipts() throws Exception {
        for (String profile : List.of("OFF", "ROLLING")) assertDeliveryVisibility(profile, ResetTiming.NONE);
    }

    /** 模型生成旧答复后，分别在草稿登记前、登记后插入重置，检查近期原文与下一次摘要。 */
    @Test public void lateLegacyAndRollingReceiptsCannotRestoreAReplyGeneratedBeforeReset() throws Exception {
        for (String profile : List.of("OFF", "ROLLING")) {
            assertDeliveryVisibility(profile, ResetTiming.BEFORE_DRAFT_RECORD);
            assertDeliveryVisibility(profile, ResetTiming.BEFORE_RECEIPT);
        }
    }

    /** 大档先通过合法引用确认提要；以供应商收到的完整文本作基线，便于辨认可见标签占用的预算。 */
    private static String commitLargeProfile(Path dir, int summaryUnits, String tail) throws Exception {
        String statement = ("杨梅桥南岸步道本周开放。" + "沿线通行。".repeat(summaryUnits)).substring(0, summaryUnits);
        try (var f = fixture(dir, LARGE)) {
            f.inbound(CHAIN, OLD_SOURCE + "旧".repeat(36_000));
            f.inbound(CHAIN, tail);
            f.summary = call -> topic(statement, call.entries().get(0).getAsJsonObject().get("index").getAsInt());
            f.turn(CHAIN, "保存本轮安排。");
            var block = history(f, "plan_turn").stream().filter(message -> message.text().contains("同会话历史提要"))
                    .findFirst().orElseThrow().text();
            assertTrue(block.contains(statement));
            assertTrue(block.length() <= LARGE / 12);
            return block;
        }
    }

    /** 从配置指定的普通回复入口取草稿，保留发起它的 Trace。渠道确认成功时按该 Trace 生成真实回执字段。 */
    private void assertDeliveryVisibility(String profile, ResetTiming timing) throws Exception {
        Path dir = folder.newFolder().toPath();
        try (var f = new ChatCompactionFixture(dir, OPENAI_CHAT, "contextCompression=" + profile + "\n")) {
            f.inbound(CHAIN, OLD_SOURCE);
            String request = "按这份公告怎么安排？";
            var trace = Trace.forChain(f.audit, "console", CHAIN, f.inbound(CHAIN, request));
            f.reply = call -> call.messages().stream().anyMatch(message -> message.text().contains(OLD_SOURCE))
                    ? OLD_ANSWER : "没有看到公告。";
            var response = f.entry(Optional.empty()).send(request, trace);
            assertEquals(OLD_ANSWER, response.data().get("text"));
            if (timing == ResetTiming.BEFORE_DRAFT_RECORD) reset(f);
            int draft = trace.write(response.causedBy(), "chat-worker", response.type(), response.data());
            if (timing == ResetTiming.BEFORE_RECEIPT) reset(f);
            trace.write(draft, "console", "console_response_written", trace.delivery(Map.of()));
        }
        try (var f = fixture(dir, SMALL)) {
            f.turn(CHAIN, QUESTION);
            assertEquals("近期原文 " + profile + " " + timing, timing == ResetTiming.NONE,
                    history(f, "plan_turn").contains(new Message("assistant", OLD_ANSWER)));
            f.inbound(CHAIN, recent(1_800));
            f.inbound(CHAIN, recent(1_400));
            f.turn(CHAIN, "整理这段历史。");
            var attempts = f.calls("submit_chat_summary");
            assertEquals(1, attempts.size());
            boolean summarized = attempts.getFirst().entries().asList().stream().map(JsonElement::getAsJsonObject)
                    .anyMatch(row -> "MISTY".equals(row.get("kind").getAsString())
                            && OLD_ANSWER.equals(row.get("text").getAsString()));
            assertEquals("可用于压缩的原文 " + profile + " " + timing, timing == ResetTiming.NONE, summarized);
        }
    }

    private static void reset(ChatCompactionFixture f) throws Exception {
        int correction = f.inbound(CHAIN, "公告已经改期。");
        f.audit.write(CHAIN, correction, "memory", "memory_context_reset", Map.of("materials",
                List.of("步道现在周六开放。"), "input_id", f.audit.runId() + ":" + correction + "-refresh"));
    }

    private static String recent(int units) {
        return "最近的步道记录" + "岸".repeat(units - "最近的步道记录".length());
    }

    private static String topic(String text, int source) {
        return JSON.toJson(Map.of("items", List.of(Map.of("kind", "TOPIC", "text", text,
                "sources", List.of(source))), "closed", List.of()));
    }

    private static ChatCompactionFixture fixture(Path dir, int budget) throws Exception {
        return new ChatCompactionFixture(dir, OPENAI_CHAT, "contextCompression=ROLLING\nhistoryBudgetUnits=" + budget + "\n");
    }

    private static List<Message> history(ChatCompactionFixture f, String name) {
        List<Message> messages = f.calls(name).getLast().messages();
        return messages.subList(1, messages.size() - 1);
    }
}
