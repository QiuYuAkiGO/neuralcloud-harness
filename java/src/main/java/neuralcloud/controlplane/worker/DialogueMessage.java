package neuralcloud.controlplane.worker;

/**
 * 会话上下文中的单条历史对话消息，由说话人角色与纯文本正文组成。
 * 主要用于在 Chat Worker 多轮续聊与轮次规划时按顺序拼装前缀对话历史。
 */
public record DialogueMessage(Speaker speaker, String text) {
    /** 对话双方角色：用户发言（{@link #USER}）与角色小雾已送达回复（{@link #MISTY}）。 */
    public enum Speaker { USER, MISTY }
}
