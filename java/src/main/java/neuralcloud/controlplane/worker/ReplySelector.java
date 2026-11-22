package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface ReplySelector {
    enum Decision { REPLY, SKIP }

    // DIRECT 由渠道根据私聊、点名或引用关系判定。群里的普通发言使用 AMBIENT。
    enum Addressing { DIRECT, AMBIENT }

    record Context(Addressing addressing, String message, List<String> recentMessages) {
        public Context {
            Objects.requireNonNull(addressing, "addressing");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("参与判断的 message 须为非空正文");
            }
            recentMessages = List.copyOf(recentMessages);
        }

        public static Context direct(String message) {
            return new Context(Addressing.DIRECT, message, List.of());
        }
    }

    // 只判断是否参与。调用失败时抛出异常，由调用方记录失败。
    Decision selectReply(Context context) throws IOException;
}
