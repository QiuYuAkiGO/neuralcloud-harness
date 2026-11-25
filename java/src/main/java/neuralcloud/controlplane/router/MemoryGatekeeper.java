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
            "(?iu)(?>请|那|你的|你|要不|能不能|能否|能|可不可以|可以|麻烦你|帮我|还是|不妨|现在|试着|再|重新|主动|仔细|好好|去|一下子|一下|一遍|一次|一查"
                    + "|查一查|查查|查询|查|检索|搜一搜|搜搜|搜索|翻一翻|翻翻|翻|看一看|看看|看|想一想|想想|想|回忆|核实|核对|确认|检查|我的|之前的|记忆库|记忆|记录|历史|吧|呢|吗|呀|啊|啦)+");
    private static final Pattern RECHECK = Pattern.compile("查|检索|搜|翻|想|回忆|核实|核对|确认|检查");
    private static final Pattern CREATOR = Pattern.compile("创造者|创建者|创作者|谁(?:创造|创建|开发|设计|制作)(?:了|的)?你|谁(?:把你|将你)(?:创造|开发)");
    private static final Pattern SENDER = Pattern.compile("我是谁|我叫什么|我的称呼|认得我|记得我是谁");
    private static final Pattern PUNCT = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    // 分句保留请求和铺垫的边界。口语里“好了要不再查查”也可以没有逗号。
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile(
            "[，,。；;！？!?：:\\r\\n…]+|(?=要不|不妨|麻烦你|能不能|能否|请再|你再)|(?<=[了啦])(?=再)");
    private static final Pattern PREFACE_END = Pattern.compile(
            "了(?:吧|呢|呀|啊)?$|啦$|过后$|之后$|以后$|的话$|就绪$|正常$|恢复$|完成$|成功$|完毕$|没问题$"
                    + "|^(?:嗯+|好[的吧]?|那[么好]?|那?你|这样啊|不好意思|麻烦了|说实话|要不|请问)$");
    private static final Pattern NEW_SUBJECT = Pattern.compile(
            "换(?:一?个)?(?:话题|问题)|关于|至于|我想(?:问|知道)|请问.+|帮我.+|谁|什么|哪[个些里位]|多少|怎么|是否|请(?:你)?(?:查|看|检查|执行|打开|重启)");

    // 只提取问句里明确的人物槽位。提到人名、询问谁提及某人仍走普通主题查询。
    private static final String PERSON = "([^，,。；;！？!?\\r\\n]{1,256}?)";
    private static final String END = "\\s*(?:呢|吗|呀|啊)?[。？?！!]*\\s*$";
    private static final Pattern OPINION = Pattern.compile("^(?:请问[，,]?\\s*)?(?:那)?(?:你(?:是)?怎么看(?:待)?\\s*"
            + PERSON + "|你对\\s*" + PERSON + "\\s*(?:有(?:什么|哪些)印象|的印象(?:如何|怎么样)|印象(?:如何|怎么样)))" + END);
    private static final Pattern UTTERANCE = Pattern.compile("^" + PERSON
            + "\\s*(?:以前|之前|曾经|曾|过去)?\\s*(?:说过|提到过|谈过|提过)\\s*"
            + "(?:(?:哪些|什么)?\\s*关于\\s*(.+?)\\s*的话|(?:什么|哪些话)|(.+?))?" + END);

    /** 空查询计划表示本轮略过主题检索。只查既有记录的行动由这一只读能力兑现。 */
    public record Decision(Optional<QueryKind> kind, String query, String reason, boolean memoryOnly,
                           String authorRef, String topicFallback) {
        private Decision(Optional<QueryKind> kind, String query, String reason, boolean memoryOnly) {
            this(kind, query, reason, memoryOnly, null, null);
        }

        /** “怎么看 X”也可能问事物。只有完整查找确认没有这个人时使用原话题。 */
        public Decision asTopic() {
            return new Decision(kind, topicFallback, "current_topic", memoryOnly);
        }

        private static Decision skip(String reason) { return new Decision(Optional.empty(), "", reason, false); }
    }

    private MemoryGatekeeper() {}

    /** 未协商作者协议的调用方只使用原有的主题和本人查询。 */
    public static Decision decide(String text, List<DialogueMessage> dialogue, List<QueryKind> kinds) {
        return decide(text, dialogue, kinds, false);
    }

    /** 省略话题的追问只向后查看经过版本核验的同会话用户消息，不从角色旧答案提取事实。 */
    public static Decision decide(String text, List<DialogueMessage> dialogue, List<QueryKind> kinds, boolean authorHistory) {
        String topic = text.strip();
        String compact = PUNCT.matcher(topic).replaceAll("");
        if (compact.isEmpty() || GREETING.matcher(compact).matches()) return Decision.skip("greeting");
        boolean followup = followup(topic);
        if (followup) {
            topic = "";
            for (var message : dialogue.reversed()) {
                if (message.speaker() != DialogueMessage.Speaker.USER) continue;
                String prior = PUNCT.matcher(message.text()).replaceAll("");
                if (!prior.isEmpty() && !GREETING.matcher(prior).matches() && !followup(message.text())) {
                    topic = message.text();
                    break;
                }
            }
            if (topic.isEmpty()) return new Decision(Optional.empty(), "", "unresolved_reference", true);
        }
        // 创造者或发言人的提问只查证关系；明确另有现场动作的复合请求保留原有执行入口。
        boolean readOnly = followup || topic.matches("(?is).*(?:谁|什么|哪位|记得|知道|认得|记忆|称呼|[？?]).*");
        if (authorHistory) {
            var author = person(topic.strip());
            if (author.isPresent()) return author.get();
        }
        if (CREATOR.matcher(topic).find())
            return new Decision(Optional.of(QueryKind.HISTORY), "创造者", "creator_relation", readOnly);
        if (kinds.contains(QueryKind.SELF_IDENTITY) && SENDER.matcher(topic).find())
            return new Decision(Optional.of(QueryKind.SELF_IDENTITY), "", "sender_identity", readOnly);
        String query = keyword(topic.strip());
        if (!MemoryCall.validQuery("HISTORY", query)) return Decision.skip("unresolved_reference");
        return new Decision(Optional.of(QueryKind.HISTORY), query, followup ? "previous_topic" : "current_topic",
                followup && (compact.contains("记忆") || compact.contains("记录") || compact.contains("历史")));
    }

    /** 主题查询的 256 字符上限以 Unicode 码点计。 */
    private static String keyword(String topic) {
        return topic.codePointCount(0, topic.length()) <= 256 ? topic
                : topic.substring(0, topic.offsetByCodePoints(0, 256)).stripTrailing();
    }

    /** 姓名只从明确问句边界取得，归属账号随后由渠道与确认目录共同确定。 */
    private static Optional<Decision> person(String topic) {
        var opinion = OPINION.matcher(topic);
        var speech = UTTERANCE.matcher(topic);
        String ref, query = "", fallback = null;
        if (opinion.matches()) {
            ref = (opinion.group(1) != null ? opinion.group(1) : opinion.group(2)).strip();
            fallback = keyword(topic);
        } else if (speech.matches()) {
            ref = speech.group(1).strip();
            if (speech.group(2) != null) query = speech.group(2).strip();
            else if (speech.group(3) != null) query = speech.group(3).strip();
        } else return Optional.empty();
        if (ref.matches("谁|我|你|他|她|它|自己|你自己|我自己|别人|大家|有人|.*(?:谁|哪些人)")
                || !MemoryCall.validQuery("HISTORY", query, ref)) return Optional.empty();
        return Optional.of(new Decision(Optional.of(QueryKind.HISTORY), query, "qq_author_history", true, ref, fallback));
    }

    /** 仅当本轮操作省略了对象时回到上一问。铺垫可以有多句，任何明确的新问题或动作仍使用本轮原文。 */
    private static boolean followup(String text) {
        if (!RECHECK.matcher(text).find()) return false;
        if (bareFollowup(PUNCT.matcher(text).replaceAll(""))) return true;
        boolean found = false;
        for (String clause : CLAUSE_BOUNDARY.split(text)) {
            String words = PUNCT.matcher(clause).replaceAll("");
            if (words.isEmpty()) continue;
            if (bareFollowup(words)) {
                found = true;
            } else if (found || NEW_SUBJECT.matcher(words).find()
                    || !(PREFACE_END.matcher(words).find() || GREETING.matcher(words).matches())) {
                return false;
            }
        }
        return found;
    }

    private static boolean bareFollowup(String words) {
        return words.length() <= 96 && FOLLOWUP.matcher(words).matches() && RECHECK.matcher(words).find();
    }
}
