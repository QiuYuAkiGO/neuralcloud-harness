package neuralcloud.controlplane.memory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

/** 不等待真实的 30 分钟，直接检验排队边界、同时触发和剩余原文的原始确认时间。 */
public class ObserveQueueTest {
    @Rule public TemporaryFolder root = new TemporaryFolder();

    private static final class Time extends Clock {
        long ms = 1700000000000L;

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }

    private void confirmed(MemoryJournal db, long room, int message, long sequence)
            throws Exception {
        String key = room + ":" + message;
        db.enqueue(
                key,
                ResidentV2.object("event_id", key),
                "s",
                new ObserveQueue.Origin(12345, room, 1, "" + message, "run", message));
        db.ack(
                key,
                ResidentV2.object(
                        "status",
                        "DURABLE",
                        "record_id",
                        "%064x".formatted(sequence),
                        "revision",
                        1,
                        "seq",
                        sequence));
    }

    @Test
    public void thresholdsArePerGroupAndClaimRetainsOriginalAgeAcrossRestart() throws Exception {
        var time = new Time();
        long start = time.ms;
        var file = root.getRoot().toPath().resolve("journal.sqlite");
        long a = 23456, b = 34567, c = 45678;
        Map<Long, Long> policies = Map.of(a, 1L, b, 1L, c, 1L);
        try (var db = new MemoryJournal(file, time)) {
            var q = new ObserveQueue(db, "s", () -> 25L);
            q.prepareEpochs(12345, policies);
            q.applyEpochs(12345, policies);
            for (int i = 1; i <= 25; i++) confirmed(db, a, i, i);
            assertEquals(0, q.status(12345, a, 1, true).pending());
            for (int i = 1; i <= 19; i++) confirmed(db, a, 25 + i, 25 + i);
            confirmed(db, b, 1, 45);
            for (int i = 1; i <= 19; i++) confirmed(db, c, i, 45 + i);
            time.ms += ObserveQueue.WAIT - 1000;
            assertEquals(start + ObserveQueue.WAIT, q.enqueueDue(12345));
            assertTrue(q.outstanding(12345).isEmpty());
            confirmed(db, a, 45, 65);
            q.enqueueDue(12345);
            assertEquals(
                    List.of(a),
                    q.outstanding(12345).stream().map(ObserveQueue.Batch::room).toList());
            time.ms += 1000;
            confirmed(db, c, 20, 66);
            q.enqueueDue(12345);
            q.enqueueDue(12345);
            assertEquals(3, q.outstanding(12345).size());
            var queued =
                    q.auditPending().stream()
                            .filter(e -> e.type().equals("observe_queued"))
                            .toList();
            assertEquals(
                    List.of("COUNT", "AGE", "COUNT"),
                    queued.stream().map(e -> e.data().get("reason").getAsString()).toList());
            var batch = q.outstanding(12345).getFirst();
            var members = q.sources(batch);
            q.claim(batch, members);
            confirmed(db, a, 46, 67);
            assertEquals(21, q.status(12345, a, 1, true).pending());
        }
        try (var db = new MemoryJournal(file, time)) {
            var q =
                    new ObserveQueue(
                            db,
                            "s",
                            () -> {
                                throw new AssertionError("重启不能重置启用水位");
                            });
            q.prepareEpochs(12345, policies);
            var batch = q.outstanding(12345).getFirst();
            assertEquals(20, q.sources(batch).size());
            q.complete(batch, null);
            assertEquals(1, q.status(12345, a, 1, true).pending());
            assertEquals(time.ms + ObserveQueue.WAIT, q.enqueueDue(12345));
            time.ms += ObserveQueue.WAIT;
            q.enqueueDue(12345);
            assertEquals(1, q.outstanding(12345).stream().filter(bat -> bat.room() == a).count());
        }
    }
}
