package neuralcloud.controlplane.master;

import com.google.gson.JsonParser;
import neuralcloud.controlplane.memory.MemoryCall.EvidenceRef;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 核对意图边界使用本轮序号映射确切版本，并拒绝无依据的印象及模型的隐式类型转换。 */
public class MasterIntentTest {
    private static final String INTENT = """
            {"assessment":"根据两次原话，我目前倾向于以更正为准","replyIntent":"告知周六看展",
             "evidence":[2,1],"impression":"根据用户的更正，我目前倾向于沿用新安排","needsReply":true}
            """;
    private static final List<EvidenceRef> OFFERED = List.of(new EvidenceRef("a".repeat(64), 1),
            new EvidenceRef("b".repeat(64), 3));

    @Test
    public void resolvesInSubmittedOrderAndAllowsAnEmptyQueryResult() throws Exception {
        var intent = MasterIntent.parse(INTENT, OFFERED);
        assertEquals(List.of(OFFERED.get(1), OFFERED.get(0)), intent.evidence());
        assertTrue(intent.needsReply());

        var empty = JsonParser.parseString(INTENT).getAsJsonObject();
        empty.add("evidence", JsonParser.parseString("[]"));
        empty.addProperty("impression", "");
        empty.addProperty("needsReply", false);
        var withoutSources = MasterIntent.parse(empty.toString(), List.of());
        assertTrue(withoutSources.evidence().isEmpty());
        assertFalse(withoutSources.needsReply());
    }

    @Test
    public void rejectsInvalidFieldsWithoutCoercingOrInventingEvidence() {
        var input = JsonParser.parseString(INTENT).getAsJsonObject();
        for (String indices : List.of("[3]", "[0]", "[1.5]", "[\"1\"]", "[true]", "null", "{}")) {
            input.add("evidence", JsonParser.parseString(indices));
            assertInvalid(input.toString(), "evidence");
        }
        input.add("evidence", JsonParser.parseString("[]"));
        assertInvalid(input.toString(), "impression");
        input.addProperty("impression", "");
        input.addProperty("needsReply", "false");
        assertInvalid(input.toString(), "needsReply");
        input.remove("needsReply");
        assertInvalid(input.toString(), "needsReply");
        input.addProperty("other", true);
        assertInvalid(input.toString(), "other");
        assertInvalid(INTENT.replace("\"needsReply\":true", "\"needsReply\":false,\"needsReply\":true"), "不重复");
    }

    @Test
    public void observationUsesOnlyTheFixedSourceNumbers() throws Exception {
        assertEquals(
                OFFERED.reversed(),
                MasterIntent.parseImpression(
                                "{\"impression\":\"根据两条原话，我目前倾向于继续观察\",\"evidence\":[2,1]}",
                                OFFERED)
                        .evidence());
        assertEquals(
                "",
                MasterIntent.parseImpression("{\"impression\":\"\",\"evidence\":[]}", OFFERED)
                        .impression());
        for (var args :
                List.of(
                        "{\"impression\":\"\",\"evidence\":[1]}",
                        "{\"impression\":\"根据发言，我目前倾向于等待\",\"evidence\":[3]}",
                        "{\"impression\":\"猜测\",\"evidence\":[1]}",
                        "{\"impression\":\"根据发言，我目前倾向于等待\",\"evidence\":[]}",
                        "{\"impression\":\"\",\"evidence\":[],\"replyIntent\":\"发言\"}"))
            assertThrows(
                    MasterIntent.InvalidSubmission.class,
                    () -> MasterIntent.parseImpression(args, OFFERED));
    }

    /** 每次拒绝都应给产生方同一种契约错误和可用于补交的字段或规则。 */
    private static void assertInvalid(String input, String rule) {
        var error = assertThrows(MasterIntent.InvalidSubmission.class, () -> MasterIntent.parse(input, OFFERED));
        assertTrue(error.getMessage(), error.getMessage().contains(rule));
    }
}
