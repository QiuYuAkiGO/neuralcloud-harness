package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 单轮冻结的模型输入。在每个使用该投影的模型调用及最终送达前复核同一组依据。 */
public record DialogueHistory(List<DialogueMessage> messages, Map<String, Long> sources, Check check) {
    @FunctionalInterface
    public interface Check { void verify() throws IOException; }

    public DialogueHistory {
        messages = List.copyOf(messages);
        sources = Map.copyOf(sources);
    }

    /** 旧配置直接使用原文，不产生额外 I/O。 */
    public static DialogueHistory plain(List<DialogueMessage> messages) {
        return new DialogueHistory(messages, Map.of(), () -> {});
    }
}
