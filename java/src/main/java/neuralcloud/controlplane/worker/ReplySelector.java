package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 群聊/对话参与决策接口（判定当前轮次角色是否应当发言）。
 * 职责边界：仅根据寻址方式、本轮消息与近期群聊上下文输出 {@link Decision#REPLY} 或 {@link Decision#SKIP}，
 * 不生成最终回复正文；模型调用或协议失败时直接抛出 {@link IOException} 交由上层审计。
 */
@FunctionalInterface
public interface ReplySelector {
    /** 参与决策结论：允许进入回复生成（{@link #REPLY}）或本轮保持沉默（{@link #SKIP}）。 */
    enum Decision { REPLY, SKIP }

    /** 消息寻址类型：{@link #DIRECT} 由渠道根据私聊、@点名或引用关系判定；群内普通旁听发言为 {@link #AMBIENT}。 */
    enum Addressing { DIRECT, AMBIENT }

    /** 参与判定输入上下文：不可变封装寻址类型、非空当前消息及同频道近期发言切片。 */
    record Context(Addressing addressing, String message, List<String> recentMessages) {
        public Context {
            Objects.requireNonNull(addressing, "addressing");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("参与判断的 message 须为非空正文");
            }
            recentMessages = List.copyOf(recentMessages);
        }

        /** 快捷构造直接对话（私聊或控制台）上下文，无需附加群聊近期旁听记录。 */
        public static Context direct(String message) {
            return new Context(Addressing.DIRECT, message, List.of());
        }
    }

    /** 判断角色是否参与本轮对话；调用或解析失败时抛出异常，由调用方记录失败，不得静默吞错。 */
    Decision selectReply(Context context) throws IOException;
}
