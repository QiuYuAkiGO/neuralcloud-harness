package neuralcloud.controlplane.orchestration;

import com.google.gson.JsonObject;
import neuralcloud.controlplane.channel.QqGroups;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Exchange;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.memory.ObserveEvidence;
import neuralcloud.controlplane.memory.ObserveQueue;
import neuralcloud.controlplane.memory.ObserveQueue.Batch;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.router.RouterMemoryWriter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static neuralcloud.controlplane.channel.QqGroups.Availability.*;

/** P3 调度的时钟和 Master 执行分属两个线程。后台只持有群取证、候选提交与审计能力。 */
public final class ObserveScheduler implements AutoCloseable {
    /** 前台可并行使用 Master；P3 仅当没有已到达的前台工作时领取一个批次。 */
    public static final class Foreground {
        @FunctionalInterface
        public interface Call<T> {
            T run() throws IOException, InterruptedException;
        }

        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private volatile Runnable onIdle = () -> {};

        public <T> T run(Call<T> call) throws IOException, InterruptedException {
            var permit = lock.readLock();
            permit.lockInterruptibly();
            try {
                return call.run();
            } finally {
                permit.unlock();
                onIdle.run();
            }
        }

        public Exchange wrap(Exchange exchange) {
            return (text, trace) -> run(() -> exchange.send(text, trace));
        }
    }

    private final long account;
    private final ObserveQueue queue;
    private final QqGroups groups;
    private final ObserveEvidence reads;
    private final RouterMemoryWriter router;
    private final AuditLog audit;
    private final Master master;
    private final Foreground foreground;
    private final int maxTokens;
    private final double temperature;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("qq-observe-master").factory());
    private final Thread timer;

    public ObserveScheduler(
            long account,
            ObserveQueue queue,
            QqGroups groups,
            ObserveEvidence reads,
            RouterMemoryWriter router,
            AuditLog audit,
            Master master,
            Foreground foreground,
            int maxTokens,
            double temperature) {
        this.account = account;
        this.queue = queue;
        this.groups = groups;
        this.reads = reads;
        this.router = router;
        this.audit = audit;
        this.master = master;
        this.foreground = foreground;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        foreground.onIdle = queue::wake;
        timer = Thread.ofVirtual().name("qq-observe-scheduler").unstarted(this::schedule);
    }

    public void start() {
        timer.start();
    }

    /** 首条确认时间决定下一次唤醒；新原文、前台释放、重连、任务结束都能提前唤醒。 */
    private void schedule() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long seen = queue.generation();
                long until;
                try {
                    until = queue.enqueueDue(account);
                    List<ObserveQueue.Audit> pendingAudit;
                    do {
                        pendingAudit = queue.auditPending();
                        for (var row : pendingAudit) {
                            audit.observation(row);
                            queue.published(row.key());
                        }
                    } while (!pendingAudit.isEmpty());
                    if (!busy.get()) {
                        for (var b : queue.outstanding(account)) {
                            if (b.retryAt() > queue.now()) {
                                until = Math.min(until, b.retryAt());
                                continue;
                            }
                            var permission = groups.availability(b.room(), b.policy());
                            if (permission == DISCONNECTED) continue;
                            if (b.candidate() == null
                                    && (permission != CURRENT
                                            || foreground.lock.getReadLockCount() > 0
                                            || foreground.lock.hasQueuedThreads())) continue;
                            busy.set(true);
                            worker.execute(() -> run(b));
                            break;
                        }
                    }
                } catch (IOException failure) {
                    failure.printStackTrace(System.err);
                    until = queue.now() + 5000;
                }
                queue.await(seen, until);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(Batch batch) {
        try {
            if (batch.candidate() != null) reconcile(batch);
            else evaluate(batch);
        } catch (IOException failure) {
            failure.printStackTrace(System.err);
            try {
                queue.retry(batch, failure);
            } catch (IOException journalFailure) {
                journalFailure.printStackTrace(System.err);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            busy.set(false);
            queue.wake();
        }
    }

    /** 本群受众由已认证账号、群号构造；后台没有私聊身份，也没有 OneBot 或 Worker 派单入口。 */
    private Trace.Scope scope(Batch b) {
        return new Trace.Scope(
                "openqq",
                b.chain(),
                audit.runId(),
                Optional.empty(),
                Optional.of(
                        new Trace.QqGroup(b.account(), b.room(), b.account(), true, b.policy())));
    }

    private void evaluate(Batch b) throws IOException, InterruptedException {
        var permit = foreground.lock.writeLock();
        // 带超时的 tryLock 遵循公平顺序，防止新的 P3 批次抢在已等待的前台请求之前。
        if (!permit.tryLock(0, TimeUnit.MILLISECONDS)) return;
        ResidentV2.PendingReceipt pending;
        Batch sending;
        try {
            if (groups.availability(b.room(), b.policy()) != CURRENT) return;
            var scope = scope(b);
            var sources = queue.sources(b);
            var packet = reads.read(scope, sources);
            synchronized (groups) {
                if (groups.availability(b.room(), b.policy()) != CURRENT) return;
                if (b.state().equals("QUEUED")) queue.claim(b, packet.members());
                else if (packet.members().size() != sources.size()) {
                    queue.requeue(b);
                    return;
                }
            }
            var refs = packet.refs();
            var comparison =
                    refs.isEmpty()
                            ? List.<String>of()
                            : reads.comparison(
                                    scope, packet, queue.recentImpressions(b.account(), b.room()));
            String inputId = UUID.randomUUID().toString();
            synchronized (groups) {
                if (groups.availability(b.room(), b.policy()) != CURRENT) return;
                queue.evidence(b, inputId, packet, comparison);
            }
            var submission =
                    refs.isEmpty()
                            ? null
                            : master.observe(
                                    packet.material(comparison), refs, maxTokens, temperature);
            if (groups.availability(b.room(), b.policy()) != CURRENT) return;
            // 空结论也必须复核，不能因原文在模型调用期间更正或撤回而错误地推进游标。
            if (!reads.read(scope, packet.members()).equals(packet))
                throw new IOException("Master 评价期间群原文发生变更，须重新取证");
            synchronized (groups) {
                if (groups.availability(b.room(), b.policy()) != CURRENT) return;
                if (submission == null) {
                    queue.complete(b, null);
                    return;
                }
                var judgment = submission.judgment();
                queue.submitted(
                        b,
                        Map.of(
                                "tool",
                                "submit_impression",
                                "toolCallId",
                                submission.callId(),
                                "inputId",
                                inputId,
                                "offered",
                                refs,
                                "submission",
                                judgment));
                if (judgment.impression().isEmpty()) {
                    queue.complete(b, null);
                    return;
                }
                var candidate =
                        router.observationCandidate(
                                scope,
                                "qq-impression:" + UUID.randomUUID(),
                                Instant.ofEpochMilli(queue.now()),
                                judgment);
                queue.sending(b, candidate);
                sending =
                        new Batch(
                                b.id(), b.account(), b.room(), b.policy(), "SENDING", 0, candidate);
                pending = router.sendObservation(candidate);
            }
        } finally {
            permit.unlock();
        }
        finish(sending, pending);
    }

    /** 已发出的命令始终先 GET。只有确认缺席且原策略仍生效，才以原键和原载荷重发。 */
    private void reconcile(Batch b) throws IOException, InterruptedException {
        var receipt = router.observationReceipt(b.candidate().get("event_id").getAsString());
        if (receipt.isPresent()) {
            queue.complete(b, receipt.get());
            return;
        }
        ResidentV2.PendingReceipt pending;
        synchronized (groups) {
            var permission = groups.availability(b.room(), b.policy());
            if (permission == RETIRED) {
                queue.cancelUncommitted(b);
                return;
            }
            if (permission == DISCONNECTED) return;
            pending = router.sendObservation(b.candidate());
        }
        finish(b, pending);
    }

    private void finish(Batch b, ResidentV2.PendingReceipt pending)
            throws IOException, InterruptedException {
        JsonObject receipt;
        try {
            receipt = pending.await();
        } catch (ResidentV2.SourceChanged rejected) {
            synchronized (groups) {
                queue.refused(b, rejected.getMessage());
                if (groups.availability(b.room(), b.policy()) == RETIRED)
                    queue.cancelUncommitted(b);
            }
            return;
        }
        queue.complete(b, receipt);
    }

    /** 停止领取后再中断在途调用，未确认的候选留给下一次启动按原键对账。 */
    @Override
    public void close() throws InterruptedException {
        timer.interrupt();
        timer.join();
        worker.shutdownNow();
        worker.close();
    }
}
