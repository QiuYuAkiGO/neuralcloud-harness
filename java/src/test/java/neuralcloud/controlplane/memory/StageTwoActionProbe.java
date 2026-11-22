package neuralcloud.controlplane.memory;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.worker.ServerStatus;
import neuralcloud.controlplane.worker.TaskWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** 跨进程验收使用正式 TaskWorker 的行动前检查，采样器只计数，不执行外部操作。 */
public final class StageTwoActionProbe {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[2]);
        boolean revoked = args[3].equals("revoked");
        try (var audit = AuditLog.open(data);
                var journal = new MemoryJournal(data.resolve("worker.sqlite"));
                var http = HttpClient.newHttpClient()) {
            var client =
                    new ResidentV2(
                            http,
                            URI.create(args[0]),
                            "misty-dev",
                            System.getenv("NC_ACCEPTANCE_TOKEN"),
                            true,
                            journal,
                            null);
            client.verify(false);
            var reads = new ConversationMemory(audit, client);
            String question = "查例会安排后读取服务器状态。";
            int inbound =
                    audit.write(
                            "p2-actions",
                            null,
                            "task-worker",
                            "operator_message",
                            Map.of("text", question));
            var trace = Trace.forChain(audit, "console", "p2-actions", inbound);
            var memory = reads.bind(trace, MemoryCall.Caller.TASK_WORKER, inbound);
            var calls = new AtomicInteger();
            var task =
                    new TaskWorker(
                            http,
                            URI.create(args[1]),
                            "fixture-task",
                            "fixture",
                            Duration.ofSeconds(12),
                            () -> {
                                calls.incrementAndGet();
                                return new ServerStatus("2026-11-22T00:00:00Z", 1, 0, 1, 1, 1, 1);
                            });
            try {
                if (task.execute(question, Optional.of(memory)).isEmpty() || revoked)
                    throw new AssertionError("行动结果错误");
            } catch (IOException error) {
                if (!revoked || !error.getMessage().contains("行动依据已经变化")) throw error;
            }
            if (calls.get() != (revoked ? 0 : 1)) throw new AssertionError("采样次数错误");
            System.out.println("samples=" + calls.get());
        }
    }
}
