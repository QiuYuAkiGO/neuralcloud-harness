package neuralcloud.controlplane.orchestration;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static neuralcloud.controlplane.orchestration.MasterEpochs.Status.FINISHED;
import static neuralcloud.controlplane.orchestration.MasterEpochs.Status.WAITING_FOR_WORKER;
import static org.junit.Assert.*;

/** 在冻结点、在途推理和落盘回执之间设置挡板，直接核对活动代和旧归档。 */
public class MasterEpochsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void pinsUnresolvedAndReplaysEveryDeltaOnceAtTheCommittedStepBoundary() throws Exception {
        var path = folder.getRoot().toPath().resolve("master.sqlite");
        var frozen = new CountDownLatch(1);
        var finishCandidate = new CountDownLatch(1);
        var candidateReady = new CountDownLatch(1);
        var inStep = new CountDownLatch(1);
        var finishStep = new CountDownLatch(1);
        var journal = EpochJournal.open(path);
        var profile = new MasterEpochs.Profile(20, 300);
        String promise = "下周三前请核对预算";
        String dated = "已完成的例行记录".repeat(3);
        try (var epochs = new MasterEpochs(journal, profile, (labels, remaining) -> {
            frozen.countDown();
            try { assertTrue(finishCandidate.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            candidateReady.countDown();
            return Set.of(); // 刻意漏选所有段。未决承诺及等待 Worker 的裁决都必须由宿主保住。
        }); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long first = epochs.open("a", "open-promise", promise);
            var outstanding = epochs.run("a", "workflow", "请核对场地", h ->
                    new MasterEpochs.Outcome<>("accepted", "等待场地 Worker 的正式回执", WAITING_FOR_WORKER));
            long closedDuringPrep = epochs.open("a", "initial", "需由当事人确认的期限");
            epochs.run("a", "old", dated, h -> new MasterEpochs.Outcome<>("ok", "例行检查已结束", FINISHED));
            var handover = pool.submit(() -> epochs.open("b", "threshold", "B".repeat(300)));
            assertTrue(frozen.await(5, TimeUnit.SECONDS));
            // S 之后既有新入站，也有旧事项的结项。最后一个主干步骤刻意停在落盘之前。
            epochs.open("a", "late", "增量一，财务邮件已到");
            epochs.resolve("a", "answer", closedDuringPrep, "增量二，当事人确认了期限");
            var running = pool.submit(() -> epochs.run("a", "active", "增量三，请核对金额", history -> {
                inStep.countDown();
                assertTrue(finishStep.await(5, TimeUnit.SECONDS));
                return new MasterEpochs.Outcome<>(42, "增量四，核对金额后回执", FINISHED);
            }));
            try {
                assertTrue(inStep.await(5, TimeUnit.SECONDS));
                finishCandidate.countDown();
                assertTrue(candidateReady.await(5, TimeUnit.SECONDS));
                assertEquals(1, journal.head().epoch());
            } finally {
                finishStep.countDown();
                finishCandidate.countDown();
            }
            assertEquals(42, running.get(5, TimeUnit.SECONDS).value().intValue());
            handover.get(5, TimeUnit.SECONDS);
            assertEquals(2, journal.head().epoch());
            String active = journal.context(2, "a", 0);
            assertTrue(active.contains("事项 " + first + " 仍待处理：" + promise));
            assertTrue(active.contains("事项 " + outstanding.matter() + " 的裁决：等待场地 Worker 的正式回执"));
            assertTrue(active.contains("事项 " + closedDuringPrep + " 已结束"));
            int previous = -1;
            for (String delta : List.of("增量一", "增量二", "增量三", "增量四")) {
                int pos = active.indexOf(delta);
                assertTrue(pos > previous);
                assertEquals(pos, active.lastIndexOf(delta));
                previous = pos;
            }
            assertFalse(active.contains("B".repeat(300)));
            assertFalse(active.contains(dated));
            assertTrue(journal.context(1, "a", 0).contains(dated));
            assertThrows(IOException.class, () -> journal.record(1, "a", "stale",
                    EpochJournal.Kind.RECEIPT, first, "旧主干迟到的回执", 0, null));
        }
        // 新进程从同一数据库恢复新代，第一次实际交付的上下文仍含未决事项和增量。
        try (var restored = new MasterEpochs(path, profile)) {
            // 先让另一受众在小窗口里再次换代，A 尚未消费的跨代增量仍须随新主干传递。
            restored.run("b", "b-first", "先处理另一受众", history ->
                    new MasterEpochs.Outcome<>("ok", "另一受众的单步已完成".repeat(5), FINISHED));
            String input = restored.run("a", "after-restart", "继续", history ->
                    new MasterEpochs.Outcome<>(history, "已检查", FINISHED)).value();
            assertTrue(input.contains(promise));
            for (String delta : List.of("增量一", "增量二", "增量三", "增量四")) assertTrue(input.contains(delta));
            assertFalse(input.contains(dated));
        }
    }

    @Test public void rejectsAnInvalidCandidateWithoutPublishingItsLeaseOrDroppingAnEvent() throws Exception {
        var path = folder.getRoot().toPath().resolve("master.sqlite");
        var journal = EpochJournal.open(path);
        try (var epochs = new MasterEpochs(journal, new MasterEpochs.Profile(8, 24),
                (labels, budget) -> Set.of(999L))) {
            long active = epochs.open("private", "one", "pending");
            assertEquals(active, epochs.open("private", "one", "pending"));
            assertThrows(IOException.class, () -> epochs.resolve("another", "wrong", active, "不得跨受众结项"));
            assertThrows(IOException.class, () -> epochs.open("private", "two", "x".repeat(24)));
            assertEquals(1, journal.head().epoch());
            assertTrue(journal.context(1, "private", 0).contains("x".repeat(24)));
        }
        try (var restored = new MasterEpochs(path, new MasterEpochs.Profile(8, 24))) {
            long next = restored.open("private", "three", "third");
            restored.resolve("private", "done", next, "已确认");
            restored.resolve("private", "done", next, "已确认");
            assertThrows(IOException.class, () -> restored.resolve("private", "duplicate", next, "重复回执"));
        }
    }

    /** 在新代号已插入、成员尚未发布时制造 SQLite 写失败，验证重启时只可能看到一个完整代。 */
    @Test public void rollsBackTheNewLeaseWhenPublishingItsMembersFails() throws Exception {
        var path = folder.getRoot().toPath().resolve("failed-cut.sqlite");
        try (var initial = EpochJournal.open(path)) { assertEquals(1, initial.head().epoch()); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.execute("""
                    CREATE TRIGGER fail_cut BEFORE INSERT ON member WHEN NEW.epoch=2
                    BEGIN SELECT RAISE(ABORT,'injected publish failure'); END""");
        }
        var profile = new MasterEpochs.Profile(8, 64);
        var journal = EpochJournal.open(path);
        try (var epochs = new MasterEpochs(journal, profile, (labels, remaining) -> Set.of())) {
            epochs.open("a", "p", "需核对的承诺");
            epochs.run("a", "past", "旧事项", h -> new MasterEpochs.Outcome<>("ok", "已经完成", FINISHED));
            assertThrows(IOException.class, () -> epochs.open("a", "new", "新".repeat(24)));
            assertEquals(1, journal.head().epoch());
            assertTrue(journal.context(1, "a", 0).contains("新".repeat(24)));
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.execute("DROP TRIGGER fail_cut");
        }
        var reopened = EpochJournal.open(path);
        try (var epochs = new MasterEpochs(reopened, profile, (labels, remaining) -> Set.of())) {
            assertEquals(1, reopened.head().epoch());
            epochs.open("a", "again", "继续");
            assertEquals(2, reopened.head().epoch());
            assertTrue(reopened.context(2, "a", 0).contains("需核对的承诺"));
            assertTrue(reopened.context(2, "a", 0).contains("新".repeat(24)));
        }
    }
}
