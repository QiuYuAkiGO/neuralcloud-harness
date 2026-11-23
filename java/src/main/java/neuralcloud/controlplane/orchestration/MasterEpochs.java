package neuralcloud.controlplane.orchestration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** 单主体 Master 的上下文及写租约。入站可以并发登记，只有持单步锁的主干可以提交决定。 */
public final class MasterEpochs implements AutoCloseable {
    /** 水位按持久分段正文的 UTF-8 字节计算，适用于本地和云端模型；未决事项与切换增量优先保全。 */
    public record Profile(int workingBytes, int triggerBytes) {
        public static final Profile DEFAULT = new Profile(2048, 4096);
        public Profile {
            if (workingBytes < 1 || workingBytes >= triggerBytes)
                throw new IllegalArgumentException("Master Epoch 水位须满足 0 < masterEpochWorkingBytes < masterEpochTriggerBytes");
        }
    }

    public enum Status { FINISHED, WAITING_FOR_WORKER, INCOMPLETE }

    /** 单步的业务结果与可在下一轮重放的文字。等待 Worker 或中断的事项继续保持未决。 */
    public record Outcome<T>(T value, String record, Status status, boolean readHistory) {
        public Outcome(T value, String record, Status status) { this(value, record, status, true); }
    }
    public record Result<T>(long matter, T value) {}
    @FunctionalInterface public interface Inference<T> {
        Outcome<T> run(String history) throws IOException, InterruptedException;
    }

    /** 候选只有被冻结的标签和软预算；返回想保留的旧段号，租约仍归主干。 */
    @FunctionalInterface interface Candidate {
        Set<Long> select(List<EpochJournal.Label> labels, long remaining);
    }

    private final EpochJournal journal;
    private final Profile profile;
    private final Candidate candidate;
    private final ReentrantLock writer = new ReentrantLock(true);
    private IOException uncommittedStep;
    private boolean preparing;
    private long lastExamined;

    public MasterEpochs(Path path, Profile profile) throws IOException {
        this(EpochJournal.open(path), profile, MasterEpochs::select);
    }

    MasterEpochs(EpochJournal journal, Profile profile, Candidate candidate) {
        this.journal = journal;
        this.profile = profile;
        this.candidate = candidate;
    }

    /** 已完成事项中先选最近的裁决，再选近期原事项和回执。保留下来的段仍按原序进入上下文。 */
    private static Set<Long> select(List<EpochJournal.Label> labels, long remaining) {
        var kept = new HashSet<Long>();
        // 稳定排序让同一优先级内维持由新到旧的顺序。
        var optional = labels.reversed().stream().filter(l -> !l.required())
                .sorted(Comparator.comparing(l -> l.kind() != EpochJournal.Kind.DECISION)).toList();
        for (var label : optional) {
            if (label.bytes() <= remaining) {
                kept.add(label.seq());
                remaining -= label.bytes();
            }
        }
        return kept;
    }

    /** 受众和来源由已认证入口绑定。单步失败时原事项留在日志中，后续换代必须携带，外部动作不会自动重发。 */
    public <T> Result<T> run(String audience, String origin, String topic, Inference<T> inference)
            throws IOException, InterruptedException {
        var admission = journal.openMatter(audience, origin, EpochJournal.Kind.REQUEST, topic);
        long matter = admission.seq();
        if (!admission.fresh()) throw new IOException("Master 入站已受理，请核对事项 " + matter + " 的原回执");
        maintain();
        Outcome<T> outcome;
        writer.lockInterruptibly();
        try {
            checkBoundary();
            EpochJournal.Head head;
            String history;
            synchronized (journal) {
                head = journal.head();
                history = journal.context(head.epoch(), audience, matter);
            }
            try {
                outcome = inference.run(history);
            } catch (IOException | InterruptedException | RuntimeException error) {
                // 未确认结果时只登记本步中断，保留原事项；没有回执就不能宣告业务已经完成。
                try {
                    commitStep(head.epoch(), audience, origin, EpochJournal.Kind.FAILED, matter, "本次处理未取得完成回执。", 0, null);
                } catch (IOException persistence) {
                    persistence.addSuppressed(error);
                    throw persistence;
                }
                throw error;
            }
            var kind = switch (outcome.status()) {
                case FINISHED -> EpochJournal.Kind.RECEIPT;
                case WAITING_FOR_WORKER -> EpochJournal.Kind.DECISION;
                case INCOMPLETE -> EpochJournal.Kind.FAILED;
            };
            commitStep(head.epoch(), audience, origin, kind, matter, outcome.record(),
                    outcome.readHistory() ? head.seq() : 0, outcome.value());
        } finally {
            writer.unlock();
        }
        maintain();
        return new Result<>(matter, outcome.value());
    }

    /** 事项可由入口显式登记或由正式 Master 请求自动创建。只有对应的完成回执才能解除保留。 */
    public long open(String audience, String origin, String topic) throws IOException, InterruptedException {
        long matter = journal.openMatter(audience, origin, EpochJournal.Kind.MATTER, topic).seq();
        maintain();
        return matter;
    }

    /** Worker 回执和操作者的明确结项作为新事件追加，可在候选准备期间到达。 */
    public void resolve(String audience, String origin, long matter, String receipt)
            throws IOException, InterruptedException {
        resolve(audience, origin, matter, receipt, null);
    }

    /** Worker 的实际回执与可重放的状态说明一并持久化。原样正文只能由后续的授权读取决定能否再次使用。 */
    public void resolve(String audience, String origin, long matter, String receipt, Object checkpoint)
            throws IOException, InterruptedException {
        synchronized (journal) {
            journal.record(journal.head().epoch(), audience, origin, EpochJournal.Kind.RECEIPT, matter, receipt, 0, checkpoint);
        }
        maintain();
    }

    /** 不能确定当前单步已落盘时保留旧租约，停止继续推理与交棒。重启从实际持久化的事务边界恢复。 */
    private void commitStep(long lease, String audience, String origin, EpochJournal.Kind kind,
                            long matter, String body, long seen, Object checkpoint) throws IOException {
        try {
            journal.record(lease, audience, origin, kind, matter, body, seen, checkpoint);
        } catch (IOException error) {
            uncommittedStep = error;
            throw error;
        }
    }

    private void checkBoundary() throws IOException {
        if (uncommittedStep != null) throw new IOException("Master 上一单步的持久状态待恢复", uncommittedStep);
    }

    /** 同一时间只有一份冻结清单。候选在两把锁之外计算，期间原主干可继续处理请求、接收回执。 */
    private void maintain() throws IOException, InterruptedException {
        if (writer.isHeldByCurrentThread()) return;
        while (true) {
            EpochJournal.Snapshot frozen;
            synchronized (journal) {
                if (preparing) return;
                var head = journal.head();
                if (head.seq() <= lastExamined || head.bytes() < profile.triggerBytes()) return;
                frozen = journal.freeze(head);
                preparing = true;
            }
            try {
                long examined = handover(frozen);
                synchronized (journal) { lastExamined = examined; }
            } finally {
                synchronized (journal) { preparing = false; }
            }
            // 上一次准备如无可剔除段，期间到达的新回执可能刚好让旧事项具备清理条件。
        }
    }

    /** 主干独立确认候选只引用冻结段，并强制补入未决事项及原受众尚未消费的换代增量。 */
    private long handover(EpochJournal.Snapshot frozen) throws IOException, InterruptedException {
        var labels = frozen.labels();
        long pinned = labels.stream().filter(EpochJournal.Label::required).mapToLong(EpochJournal.Label::bytes).sum();
        long budget = Math.max(0, profile.workingBytes() - pinned);
        var choices = candidate.select(labels, budget);
        var known = labels.stream().map(EpochJournal.Label::seq).collect(Collectors.toSet());
        long optionalBytes = labels.stream().filter(l -> !l.required() && choices.contains(l.seq()))
                .mapToLong(EpochJournal.Label::bytes).sum();
        if (!known.containsAll(choices) || optionalBytes > budget)
            throw new IOException("Epoch 候选只能选择冻结清单内的段号，选留正文须满足剩余字节预算");
        var keep = labels.stream().filter(l -> l.required() || choices.contains(l.seq())).map(EpochJournal.Label::seq).toList();
        if (keep.size() == labels.size()) return frozen.through();
        writer.lockInterruptibly();
        try {
            checkBoundary();
            return journal.switchEpoch(frozen, keep);
        } finally {
            writer.unlock();
        }
    }

    @Override public void close() throws IOException { journal.close(); }
}
