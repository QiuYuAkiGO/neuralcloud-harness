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
    private static final Map<String, Object> PROPERTIES = Map.of(
            "assessment", Map.of("type", "string", "description", "本轮评价，使用句式“" + FORM + "”"),
            "replyIntent", Map.of("type", "string", "description", "非空的回复意图，说明希望传达的意思"),
            "evidence", Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 1),
                    "description", "事实依据只填本轮记忆工具正文标出的证据序号，可为空数组"),
            "impression", Map.of("type", "string", "description", "没有新印象时填空字符串，有依据时使用句式“" + FORM + "”"),
            "needsReply", Map.of("type", "boolean", "description", "本轮是否需要向用户回复"));
    static final Map<String, Object> TOOL = Map.of("type", "function", "function", Map.of(
            "name", NAME, "description", "提交对本轮事项的判断、来源与回复意图", "strict", true,
            "parameters", Map.of("type", "object", "required", FIELDS, "additionalProperties", false,
                    "properties", PROPERTIES)));

    /** 只有提交参数有误可以由模型补交。传输、授权和版本复核的失败由各自边界处理。 */
    static final class InvalidSubmission extends IOException {
        InvalidSubmission(String rule) {
            super(NAME + " 不符合意图契约：" + rule);
        }

        InvalidSubmission(String rule, Throwable cause) {
            super(NAME + " 不符合意图契约：" + rule, cause);
        }
    }

    /** 严格解析一份完整提交。只有实际交付的编号可作为来源，空结果仍允许提交无依据、无新印象的判断。 */
    static MasterIntent parse(String arguments, List<EvidenceRef> offered) throws InvalidSubmission {
        if (!arguments.stripLeading().startsWith("{")) throw new InvalidSubmission("参数须为 JSON 对象");
        Map<String, JsonElement> fields;
        try {
            // Map 适配器会拒绝重复键，避免同一字段的后一个值掩盖前一个值。
            fields = JSON.fromJson(arguments, OBJECT);
        } catch (JsonParseException error) {
            throw new InvalidSubmission("参数须为字段不重复的合法 JSON 对象", error);
        }
        if (!fields.keySet().equals(PROPERTIES.keySet())) {
            throw new InvalidSubmission("字段须恰为 " + FIELDS + "，实际为 " + fields.keySet());
        }
        String assessment = string(fields, "assessment");
        String reply = string(fields, "replyIntent");
        String impression = string(fields, "impression");
        if (!OPINION.matcher(assessment).matches()) throw new InvalidSubmission("assessment 须使用“" + FORM + "”");
        if (reply.isBlank()) throw new InvalidSubmission("replyIntent 须为非空正文");
        if (!(fields.get("needsReply") instanceof JsonPrimitive flag && flag.isBoolean())) {
            throw new InvalidSubmission("needsReply 须为布尔值");
        }
        if (!(fields.get("evidence") instanceof JsonArray indices)) {
            throw new InvalidSubmission("evidence 须为本轮证据序号数组");
        }
        var references = new ArrayList<EvidenceRef>();
        for (JsonElement element : indices) {
            references.add(offered.get(evidenceIndex(element, offered.size()) - 1));
        }
        if (!impression.isEmpty() && (references.isEmpty() || !OPINION.matcher(impression).matches())) {
            throw new InvalidSubmission("非空 impression 须引用 evidence 并使用“" + FORM + "”");
        }
        return new MasterIntent(assessment, reply, List.copyOf(references), impression, flag.getAsBoolean());
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
