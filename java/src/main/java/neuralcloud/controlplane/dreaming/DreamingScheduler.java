package neuralcloud.controlplane.dreaming;

import com.google.gson.*;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.dreaming.DreamingFiles.Stage;
import neuralcloud.controlplane.integration.inference.InvalidModelOutput;
import neuralcloud.controlplane.memory.DreamingMemory;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.orchestration.ObserveScheduler.Foreground;
import neuralcloud.controlplane.router.RouterMemoryWriter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** 单个在途推理与独立整理时钟。只持有自己的模型、取证和确定性写入口，前台从不等待模型返回。 */
public final class DreamingScheduler implements AutoCloseable {
    private final DreamingMemory memory;
    private final RouterMemoryWriter router;
    private final DreamingModel model;
    private final Foreground foreground;
    private final Supplier<Optional<Set<String>>> scopes;
    private final Object scopeLock;
    private final DreamingFiles files;
    private final AuditLog audit;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("dreaming-model").factory());
    private final Thread clock;
    private Future<DreamingModel.Proposal> inference;
    private JsonObject offered;
    private JsonObject suspendedPosition;
    private long offeredForeground;
    private Set<String> lastScopes;

    public DreamingScheduler(
            DreamingMemory memory,
            RouterMemoryWriter router,
            DreamingModel model,
            Foreground foreground,
            Supplier<Optional<Set<String>>> scopes,
            Object scopeLock,
            AuditLog audit)
            throws IOException {
        this.memory = memory;
        this.router = router;
        this.model = model;
        this.foreground = foreground;
        this.scopes = scopes;
        this.audit = audit;
        this.scopeLock = scopeLock;
        files =
                new DreamingFiles(
                        audit.directory(),
                        model.outputDirectory().orElse(audit.directory().resolve("impressions")),
                        memory.character());
        clock = Thread.ofVirtual().name("dreaming-scheduler").unstarted(this::schedule);
    }

    public void start() {
        clock.start();
    }

    /** 渠道策略变更或推理结束可以提前唤醒，孤立故障按配置间隔重试。 */
    public void wake() {
        LockSupport.unpark(clock);
    }

    private void schedule() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    tick();
                } catch (IOException error) {
                    error.printStackTrace(System.err);
                }
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(model.intervalSeconds()));
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void tick() throws IOException, InterruptedException {
        var roster = scopes.get();
        var pending = files.pending();
        if (pending != null) {
            reconcile(pending, roster);
            return;
        }
        if (roster.isEmpty()) {
            // 动态名单未知时保留已确认授权与水位，等可信名单返回后再领取或首发。
            cancelInference();
            files.publish(memory.profiles());
            return;
        }
        var allowed = roster.get();
        if (!foreground.idle() && allowed.equals(lastScopes)) {
            cancelInference();
            files.publish(memory.profiles());
            return;
        }
        var batch = memory.batch(allowed, model.batchSize());
        lastScopes = allowed;
        files.publish(memory.profiles());
        if (inference != null
                && (!batch.equals(offered)
                        || !foreground.idle()
                        || offeredForeground != foreground.generation())) cancelInference();
        if (!foreground.idle()
                || !roster.equals(scopes.get())
                || batch.get("after").equals(batch.get("through"))) return;
        // 仍从 Memory 领取以发现权限或材料换版。挂起期间无需创建推理任务。
        if (suspendedPosition != null) {
            if (memory.sameBatch(suspendedPosition, batch)) return;
            suspendedPosition = null;
        }
        DreamingModel.Proposal proposal;
        if (inference != null) {
            if (!inference.isDone()) return;
            var done = inference;
            inference = null;
            offered = null;
            try {
                proposal = done.get();
                if (proposal == null) {
                    suspendedPosition = DreamingMemory.position(batch);
                    return;
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException error) throw error;
                if (e.getCause() instanceof InterruptedException) return;
                throw new IllegalStateException("Dreaming 推理线程异常", e.getCause());
            }
        } else if (batch.getAsJsonArray("evidence").isEmpty()) {
            proposal = new DreamingModel.Proposal(new JsonArray(), new JsonArray());
        } else {
            offered = batch;
            offeredForeground = foreground.generation();
            var task =
                    new FutureTask<DreamingModel.Proposal>(() -> infer(batch)) {
                        @Override
                        protected void done() {
                            wake();
                        }
                    };
            inference = task;
            worker.execute(task);
            return;
        }
        // 前台或群策略在模型执行期间变化时让出；Memory 在提交事务内再次核对证据。
        if (!foreground.idle() || !roster.equals(scopes.get())) return;
        var command = memory.command(batch, proposal.changes(), proposal.nameClaims());
        files.sending(command);
        send(command, allowed);
    }

    /** 串行工作线程独占同版的补正机会。null 表示挂起；合法的空数组仍须提交给 Memory。 */
    private DreamingModel.Proposal infer(JsonObject batch) throws IOException, InterruptedException {
        var position = DreamingMemory.position(batch);
        var previous = files.failure(position);
        if (previous != null) {
            switch (previous.stage()) {
                case SUSPENDED -> { return null; }
                case CORRECTING -> {
                    // 上次在补正途中退出或失去响应，继续生成会重复花费这一批的额度。
                    recordFailure(position, true, "上次补正已登记，但未取得可恢复的结果");
                    return null;
                }
                case CORRECTION_PENDING -> files.validation(position, Stage.CORRECTING, previous.detail());
            }
        }
        try {
            return model.propose(batch, previous == null ? null : previous.detail());
        } catch (InvalidModelOutput invalid) {
            recordFailure(position, previous != null, invalid.getMessage());
            throw invalid;
        }
    }

    /** 持久状态决定能否重试；审计记录本版材料的失败次数和具体字段。 */
    private void recordFailure(JsonObject position, boolean suspended, String detail) throws IOException {
        files.validation(position, suspended ? Stage.SUSPENDED : Stage.CORRECTION_PENDING, detail);
        auditFailure(position, suspended, detail);
    }

    private void auditFailure(JsonObject position, boolean suspended, String detail) throws IOException {
        audit.write("dreaming", null, "dreaming", suspended ? "dreaming_suspended" : "dreaming_correction",
                Map.of("position", position, "attempt", suspended ? 2 : 1, "detail", detail));
    }

    private void cancelInference() {
        if (inference != null) {
            inference.cancel(true);
            inference = null;
            offered = null;
        }
    }

    /** 查回执只依赖 Memory。重发须等可信名单就绪并核对原批次。 */
    private void reconcile(JsonObject pending, Optional<Set<String>> roster)
            throws IOException, InterruptedException {
        var receipt = memory.receipt(pending);
        if (receipt.isPresent()) {
            finish(pending, receipt.get());
            return;
        }
        if (roster.isEmpty()) return;
        var current = memory.batch(roster.get(), model.batchSize());
        if (memory.sameBatch(pending, current)) {
            send(pending, roster.get());
            return;
        }
        // 第一次 NOT_FOUND 和领取新批是两个事务，旧提交可能恰好在其间取得持久回执。
        var lastReceipt = memory.receipt(pending);
        if (lastReceipt.isPresent()) finish(pending, lastReceipt.get());
        else files.resolved(DreamingMemory.position(pending));
    }

    private void send(JsonObject command, Set<String> allowed)
            throws IOException, InterruptedException {
        ResidentV2.PendingReceipt receipt;
        synchronized (scopeLock) {
            if (!foreground.idle() || scopes.get().filter(allowed::equals).isEmpty()) return;
            receipt = router.sendDreaming(command);
        }
        JsonObject answer;
        try {
            answer = receipt.await();
        } catch (IOException error) {
            // 409 要重新取证。只有权威端明确拒绝字段才消耗同批补正机会；未知回执仍按原键核对。
            var position = DreamingMemory.position(command);
            if (error instanceof ResidentV2.SourceChanged) files.resolved(position);
            else if (DreamingMemory.invalidProposal(error)) {
                boolean suspend = files.failure(position) != null;
                files.rejected(position, suspend ? Stage.SUSPENDED : Stage.CORRECTION_PENDING, error.getMessage());
                auditFailure(position, suspend, error.getMessage());
            }
            throw error;
        }
        finish(command, answer);
    }

    /** 已确认的批次只报告覆盖与回执。导出失败保留本地命令，下次从权威版本重建。 */
    private void finish(JsonObject command, JsonObject receipt)
            throws IOException, InterruptedException {
        memory.checkReceipt(command, receipt);
        var allowed = scopes.get();
        if (allowed.isPresent() && !allowed.get().equals(lastScopes)) {
            memory.batch(allowed.get(), model.batchSize());
            lastScopes = allowed.get();
        }
        files.publish(memory.profiles());
        audit.write("dreaming", null, "dreaming", "dreaming_durable", Map.of("receipt", receipt));
        files.resolved(DreamingMemory.position(command));
        wake();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        clock.interrupt();
        clock.join();
        worker.shutdownNow();
        worker.close();
        files.close();
    }
}
