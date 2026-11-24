package neuralcloud.controlplane.router;

import neuralcloud.controlplane.memory.ConversationMemory.QueryKind;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.worker.DialogueMessage;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 为当轮参与回复的前台选择一次只读操作。词组解析只决定查询目的，记录及确认关系由受众查询返回。 */
public final class MemoryGatekeeper {
    private static final Pattern GREETING = Pattern.compile(
            "(?iu)(?:你好[呀啊哇]?|您好|嗨|哈[啰喽罗]|早[呀啊安上好]?|中午好|下午好|晚上好|晚安|谢谢[你啦]?|再见|拜拜|hi|hello|hey)");
    private static final Pattern FOLLOWUP = Pattern.compile(
            "(?iu)(?>请|那|你的|你|要不|能不能|可以|麻烦你|帮我|还是|能否|不妨|再|重新|主动|仔细|好好|去|一下子|一下|一遍|一次|一查"
                    + "|查查|查询|查|检索|搜搜|搜索|翻翻|翻|看看|看|想想|想|回忆|核实|核对|确认|检查|我的|之前的|记忆库|记忆|记录|历史|吧|呢|吗|呀|啊|啦)+");
    private static final Pattern RECHECK = Pattern.compile("查|检索|搜|翻|想|回忆|核实|核对|确认|检查");
    private static final Pattern CREATOR = Pattern.compile("创造者|创建者|创作者|谁(?:创造|创建|开发|设计|制作)(?:了|的)?你|谁(?:把你|将你)(?:创造|开发)");
    private static final Pattern SENDER = Pattern.compile("我是谁|我叫什么|我的称呼|认得我|记得我是谁");
    private static final Pattern PUNCT = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    /** 空查询计划表示本轮略过主题检索。只查既有记录的行动由这一只读能力兑现。 */
    public record Decision(Optional<QueryKind> kind, String query, String reason, boolean memoryOnly) {
        private static Decision skip(String reason) { return new Decision(Optional.empty(), "", reason, false); }
    }

    private MemoryGatekeeper() {}

    /** 省略话题的追问只向后查看经过版本核验的同会话用户消息，不从角色旧答案提取事实。 */
    public static Decision decide(String text, List<DialogueMessage> dialogue, List<QueryKind> kinds) {
        String topic = text.strip();
        String compact = PUNCT.matcher(topic).replaceAll("");
        if (compact.isEmpty() || GREETING.matcher(compact).matches()) return Decision.skip("greeting");
        boolean followup = followup(compact);
        if (followup) {
            topic = "";
            for (var message : dialogue.reversed()) {
                if (message.speaker() != DialogueMessage.Speaker.USER) continue;
                String prior = PUNCT.matcher(message.text()).replaceAll("");
                if (!prior.isEmpty() && !GREETING.matcher(prior).matches() && !followup(prior)) {
                    topic = message.text();
                    break;
                }
            }
            if (topic.isEmpty()) return new Decision(Optional.empty(), "", "unresolved_reference", true);
        }
        // 创造者或发言人的提问只查证关系；明确另有现场动作的复合请求保留原有执行入口。
        boolean readOnly = followup || topic.matches("(?is).*(?:谁|什么|哪位|记得|知道|认得|记忆|称呼|[？?]).*");
        if (CREATOR.matcher(topic).find())
            return new Decision(Optional.of(QueryKind.HISTORY), "创造者", "creator_relation", readOnly);
        if (kinds.contains(QueryKind.SELF_IDENTITY) && SENDER.matcher(topic).find())
            return new Decision(Optional.of(QueryKind.SELF_IDENTITY), "", "sender_identity", readOnly);
        String query = topic.strip();
        int points = query.codePointCount(0, query.length());
        if (points > 256) query = query.substring(0, query.offsetByCodePoints(0, 256)).stripTrailing();
        if (!MemoryCall.validQuery("HISTORY", query)) return Decision.skip("unresolved_reference");
        return new Decision(Optional.of(QueryKind.HISTORY), query, followup ? "previous_topic" : "current_topic",
                followup && (compact.contains("记忆") || compact.contains("记录") || compact.contains("历史")));
    }

    private static boolean followup(String text) {
        // 省略指代是一句短语；长发言直接按自身话题检索。
        return text.length() <= 96 && FOLLOWUP.matcher(text).matches() && RECHECK.matcher(text).find();
    }
}
