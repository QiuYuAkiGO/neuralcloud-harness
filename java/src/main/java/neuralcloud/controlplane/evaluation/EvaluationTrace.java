package neuralcloud.controlplane.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

final class EvaluationTrace implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    private record Event(
            int schemaVersion, String runId, int eventId, Integer causedBy, String caseId,
            String recordedAt, String type, Object data) {
    }

    private final BufferedWriter writer;
    private final String runId = UUID.randomUUID().toString();
    private int eventId;

    EvaluationTrace(Path path) throws IOException {
        writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    // 每个事件记录上游事件 ID。每写一行就刷新缓冲区，保留失败前已经记录的步骤。
    int write(String caseId, Integer causedBy, String type, Object data) throws IOException {
        int id = ++eventId;
        writer.write(JSON.toJson(new Event(1, runId, id, causedBy, caseId, Instant.now().toString(), type, data)));
        writer.newLine();
        writer.flush();
        return id;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
