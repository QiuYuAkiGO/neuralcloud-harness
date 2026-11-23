package neuralcloud.controlplane.master;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import neuralcloud.controlplane.memory.MemoryCall.EvidenceRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** Master 对本轮事项的判断。证据已经由宿主从模型所见的序号解析为记录版本，可沿审计链回查。 */
public record MasterIntent(String assessment, String replyIntent, List<EvidenceRef> evidence,
                           String impression, boolean needsReply) {
    static final String NAME = "submit_intent";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final TypeToken<Map<String, JsonElement>> OBJECT = new TypeToken<>() {};
    private static final String FORM = "根据……，我目前倾向于……";
    private static final Pattern OPINION = Pattern.compile("根据\\s*\\S.*?，我目前倾向于\\s*\\S.*",
            Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> FIELDS = List.of("assessment", "replyIntent", "evidence", "impression", "needsReply");
    private static final Map<String, Object> PROPERTIES =
            Map.of(
                    "assessment",
                            Map.of("type", "string", "description", "本轮评价，使用句式“" + FORM + "”"),
                    "replyIntent",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "给下游的完整可传达事实，写明具体答案与不确定性。群通知中用于回答的原文片段用「」标出。"),
                    "evidence",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of("type", "integer", "minimum", 1),
                                    "description",
                                    "事实依据只填本轮记忆工具正文标出的证据序号，可为空数组"),
                    "impression",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "没有新印象时填空字符串，有依据时使用句式“" + FORM + "”"),
                    "needsReply", Map.of("type", "boolean", "description", "本轮是否需要向用户回复"));
    static final Map<String, Object> TOOL = Map.of("type", "function", "function", Map.of(
            "name", NAME, "description", "提交对本轮事项的判断、来源与回复意图", "strict", true,
            "parameters", Map.of("type", "object", "required", FIELDS, "additionalProperties", false,
                    "properties", PROPERTIES)));

    /** 只有提交参数有误可以由模型补交。传输、授权和版本复核的失败由各自边界处理。 */
    static final class InvalidSubmission extends IOException {
        InvalidSubmission(String rule) {
            super("Master 提交不符合契约：" + rule);
        }

        InvalidSubmission(String rule, Throwable cause) {
            super("Master 提交不符合契约：" + rule, cause);
        }
    }

    /** 严格解析一份完整提交。只有实际交付的编号可作为来源，空结果仍允许提交无依据、无新印象的判断。 */
    static MasterIntent parse(String arguments, List<EvidenceRef> offered) throws InvalidSubmission {
        return parse(arguments, offered, false);
    }

    /** 渠道已确认必须答复时，把 needsReply 作为本轮提交契约交给模型校正。 */
    static MasterIntent parse(String arguments, List<EvidenceRef> offered, boolean replyRequired)
            throws InvalidSubmission {
        return parse(arguments, offered, replyRequired, Map.of());
    }

    /** 引用群通知的意图必须携带源版本中可逐字核对的具体答案，下游只能看见获准外发的文字。 */
    static MasterIntent parse(
            String arguments,
            List<EvidenceRef> offered,
            boolean replyRequired,
            Map<EvidenceRef, String> notices)
            throws InvalidSubmission {
        var fields = fields(arguments, FIELDS);
        String assessment = string(fields, "assessment");
        String reply = string(fields, "replyIntent");
        String impression = string(fields, "impression");
        if (!OPINION.matcher(assessment).matches()) throw new InvalidSubmission("assessment 须使用“" + FORM + "”");
        if (reply.isBlank()) throw new InvalidSubmission("replyIntent 须为非空正文");
        if (!(fields.get("needsReply") instanceof JsonPrimitive flag && flag.isBoolean())) {
            throw new InvalidSubmission("needsReply 须为布尔值");
        }
        if (replyRequired && !flag.getAsBoolean())
            throw new InvalidSubmission("本轮渠道已决定参与，needsReply 须为 true");
        var references = references(fields, offered, impression);
        var intent =
                new MasterIntent(
                        assessment,
                        reply,
                        List.copyOf(references),
                        impression,
                        flag.getAsBoolean());
        var texts = references.stream().filter(notices::containsKey).map(notices::get).toList();
        if (!texts.isEmpty()) {
            var quotes = intent.quotes();
            if (quotes.isEmpty()
                    || quotes.stream().anyMatch(q -> texts.stream().noneMatch(t -> t.contains(q)))
                    || texts.stream().anyMatch(t -> quotes.stream().noneMatch(t::contains)))
                throw new InvalidSubmission("replyIntent 须用「」逐字引用每条所选群通知中回答本题的具体原文，请保留所问各项的值");
        }
        return intent;
    }

    /** 群通知中由 Master 选定的引用须由 Chat Worker 原样保留，其他措辞仍可使用角色口吻。 */
    public List<String> quotes() {
        return Pattern.compile("「([^「」]+)」")
                .matcher(replyIntent)
                .results()
                .map(m -> m.group(1))
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** 后台工具只有评价和证据，不能表达外发意图或创建 Worker 步骤。 */
    public record Impression(String impression, List<EvidenceRef> evidence) {}

    static final String OBSERVE = "submit_impression";
    private static final List<String> OBSERVE_FIELDS = List.of("impression", "evidence");
    static final Map<String, Object> IMPRESSION_TOOL =
            Map.of(
                    "type",
                    "function",
                    "function",
                    Map.of(
                            "name",
                            OBSERVE,
                            "description",
                            "提交本批群原文产生的新印象或空结论",
                            "strict",
                            true,
                            "parameters",
                            Map.of(
                                    "type",
                                    "object",
                                    "required",
                                    OBSERVE_FIELDS,
                                    "additionalProperties",
                                    false,
                                    "properties",
                                    Map.of(
                                            "impression",
                                            PROPERTIES.get("impression"),
                                            "evidence",
                                            PROPERTIES.get("evidence")))));

    static Impression parseImpression(String arguments, List<EvidenceRef> offered)
            throws InvalidSubmission {
        var fields = fields(arguments, OBSERVE_FIELDS);
        var impression = string(fields, "impression");
        var refs = references(fields, offered, impression);
        if (impression.isEmpty() && !refs.isEmpty() || new HashSet<>(refs).size() != refs.size())
            throw new InvalidSubmission("无新印象时 evidence 须为空，有印象时每条原文只引用一次");
        return new Impression(impression, refs);
    }

    /** 两种提交共享严格的 JSON、句式和实际交付序号校验，保留各自独立的必填字段。 */
    private static Map<String, JsonElement> fields(String arguments, List<String> required)
            throws InvalidSubmission {
        if (!arguments.stripLeading().startsWith("{")) throw new InvalidSubmission("参数须为 JSON 对象");
        try {
            var fields = JSON.fromJson(arguments, OBJECT);
            if (!fields.keySet().equals(new HashSet<>(required)))
                throw new InvalidSubmission("字段须恰为 " + required + "，实际为 " + fields.keySet());
            return fields;
        } catch (JsonParseException error) {
            throw new InvalidSubmission("参数须为字段不重复的合法 JSON 对象", error);
        }
    }

    private static List<EvidenceRef> references(
            Map<String, JsonElement> fields, List<EvidenceRef> offered, String impression)
            throws InvalidSubmission {
        if (!(fields.get("evidence") instanceof JsonArray indices))
            throw new InvalidSubmission("evidence 须为本轮证据序号数组");
        var refs = new ArrayList<EvidenceRef>();
        for (var index : indices) refs.add(offered.get(evidenceIndex(index, offered.size()) - 1));
        if (!impression.isEmpty() && (refs.isEmpty() || !OPINION.matcher(impression).matches()))
            throw new InvalidSubmission("非空 impression 须引用 evidence 并使用“" + FORM + "”");
        return List.copyOf(refs);
    }

    /** 文本字段须由模型以 JSON 字符串提交。 */
    private static String string(Map<String, JsonElement> fields, String field) throws InvalidSubmission {
        if (fields.get(field) instanceof JsonPrimitive value && value.isString()) return value.getAsString();
        throw new InvalidSubmission(field + " 须为字符串");
    }

    /** 只接受可精确表示为整数的 JSON 数字，并按实际交付条数核对编号。 */
    private static int evidenceIndex(JsonElement element, int count) throws InvalidSubmission {
        String rule = "evidence 只能引用本轮实际返回的整数序号，本轮共有 " + count + " 条证据";
        if (element instanceof JsonPrimitive value && value.isNumber()) {
            try {
                int index = value.getAsBigDecimal().intValueExact();
                if (index >= 1 && index <= count) return index;
            } catch (ArithmeticException | NumberFormatException error) {
                throw new InvalidSubmission(rule, error);
            }
        }
        throw new InvalidSubmission(rule);
    }

    /** 正式控制台与审计事件使用同一份已验收意图，保持来源顺序及 needsReply 的布尔语义。 */
    public Map<String, Object> data() {
        return Map.of("assessment", assessment, "replyIntent", replyIntent, "evidence", evidence,
                "impression", impression, "needsReply", needsReply);
    }
}
