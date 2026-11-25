package neuralcloud.controlplane.memory;

/** 前台对话可独立启用的二次读取。每个值对应控制台配置中的一个手动开关。 */
public enum ChatReview {
    RECALL_SOURCES("chatReviewRecallSources"),
    BEFORE_PLAN("chatReviewBeforePlan"),
    AFTER_PLAN("chatReviewAfterPlan"),
    BEFORE_DRAFT("chatReviewBeforeDraft"),
    AFTER_DRAFT("chatReviewAfterDraft"),
    BEFORE_RETURN("chatReviewBeforeReturn"),
    DELIVERY_SOURCES("chatReviewDeliverySources"),
    QQ_MEMBERSHIP("chatReviewQqMembership");

    private final String property;

    ChatReview(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }
}
