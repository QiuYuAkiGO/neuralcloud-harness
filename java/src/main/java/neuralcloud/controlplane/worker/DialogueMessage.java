package neuralcloud.controlplane.worker;

/**
 * 会话上下文中的单条历史对话消息，由说话人角色与纯文本正文组成。
 * 主要用于在 Chat Worker 多轮续聊与轮次规划时按顺序拼装前缀对话历史。
 */
public record DialogueMessage(Speaker speaker, String text) {
    /** NOTE 是宿主给模型的历史资料块，对话界面和送达审计仅由渠道事件产生。 */
    public enum Speaker { USER, MISTY, NOTE }
}
