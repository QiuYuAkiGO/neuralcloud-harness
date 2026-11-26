package neuralcloud.controlplane.integration.inference;

import java.io.IOException;

/** 模型已返回、但提交违反契约。后台可按批次反馈补正；HTTP、授权及持久化失败由各自边界处理。 */
public class InvalidModelOutput extends IOException {
    public InvalidModelOutput(String message) { super(message); }

    public InvalidModelOutput(String message, Throwable cause) { super(message, cause); }
}
