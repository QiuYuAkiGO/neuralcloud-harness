package neuralcloud.controlplane.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 通过正式控制台配置入口验收前台复核开关。 */
public class ChatReviewConfigTest {
    private static final List<String> FIELDS = List.of(
            "chatReviewRecallSources", "chatReviewBeforePlan", "chatReviewAfterPlan",
            "chatReviewBeforeDraft", "chatReviewAfterDraft", "chatReviewBeforeReturn",
            "chatReviewDeliverySources", "chatReviewQqMembership");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 复用可启动的控制台样例，追加本用例提供的可选设置。 */
    private ConfigSource config(String settings) throws IOException {
        var file = folder.newFile().toPath();
        try (var base = ChatReviewConfigTest.class.getResourceAsStream("/evaluation/console-scenario.properties")) {
            Files.writeString(file, new String(base.readAllBytes(), StandardCharsets.UTF_8) + "\n" + settings);
        }
        return ConfigSource.file(file);
    }

    /** 旧配置可直接加载，八个键可独立混用 true 和 false。 */
    @Test public void acceptsOmittedAndMixedSwitches() throws IOException {
        assertNotNull(ConsoleConfig.load(config("")));
        var mixed = new StringBuilder();
        for (int i = 0; i < FIELDS.size(); i++) {
            mixed.append(FIELDS.get(i)).append('=').append(i % 2 == 0).append('\n');
        }
        assertNotNull(ConsoleConfig.load(config(mixed.toString())));
    }

    /** 非布尔值须点名出错字段，拼错键名也不能被静默接受。 */
    @Test public void rejectsInvalidValuesAndMisspelledSwitch() throws IOException {
        var invalid = List.of("TRUE", "False", "yes", "no", "", "true ", "0", "1");
        for (int i = 0; i < FIELDS.size(); i++) {
            String field = FIELDS.get(i);
            var input = config(field + "=" + invalid.get(i) + "\n");
            var error = assertThrows(IllegalArgumentException.class, () -> ConsoleConfig.load(input));
            assertTrue(field + " 的诊断应指出配置键，实得 " + error.getMessage(), error.getMessage().contains(field));
        }
        var misspelled = config("chatReviewBeforePlanning=true\n");
        assertThrows(IllegalArgumentException.class, () -> ConsoleConfig.load(misspelled));
    }
}
