package neuralcloud.controlplane.evaluation;

import java.io.IOException;

// 挡板事件的写入目标。返回的事件 ID 供后续事件作为 causedBy 引用。
public interface TraceSink {
    int write(String caseId, Integer causedBy, String type, Object data) throws IOException;
}
