package neuralcloud.controlplane.channel;

import com.google.gson.JsonParser;
import neuralcloud.controlplane.console.AuditLog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.ConcurrentModificationException;
import static org.junit.Assert.*;

/** 验证名单、策略版本、重新入群和普通消息接收，防止断线或重启时错误恢复回复权限。 */
public class QqGroupsTest {
    @Rule public TemporaryFolder root = new TemporaryFolder();

    @Test
    public void policyIsDurableAndAppliesAtIngress() throws Exception {
        var path = root.getRoot().toPath();
        var list =
                JsonParser.parseString(
                                "[{\"group_id\":54321,\"group_name\":\"测试\",\"member_count\":3,\"max_member_count\":200}]")
                        .getAsJsonArray();
        var message =
                JsonParser.parseString(
                                """
                {"post_type":"message","message_type":"group","self_id":12345,"group_id":54321,"user_id":67890,
                 "message_id":11,"message":[{"type":"text","data":{"text":"群消息"}}]}
                """)
                        .getAsJsonObject();
        try (var audit = AuditLog.open(path.resolve("audit"))) {
            var groups = new QqGroups(path.resolve("policy.json"), 12345, audit);
            groups.roster(list);
            assertNull(OpenQqChannel.incoming(message, 12345, 67890, 0, false, groups));
            var saved = groups.change(54321, new QqGroups.Policy(QqGroups.Mode.OBSERVE, 0));
            assertEquals(1, saved.revision());
            assertThrows(
                    ConcurrentModificationException.class,
                    () ->
                            groups.change(
                                    54321, new QqGroups.Policy(QqGroups.Mode.OBSERVE_REPLY, 0)));
            var observed = OpenQqChannel.incoming(message, 12345, 67890, 0, false, groups);
            assertFalse(observed.addressed());
            assertEquals(saved, observed.policy());
            var restored = new QqGroups(path.resolve("policy.json"), 12345, audit);
            assertNull(OpenQqChannel.incoming(message, 12345, 67890, 0, false, restored));
            restored.roster(list);
            assertEquals(saved, restored.current(54321));
            restored.roster(new com.google.gson.JsonArray());
            restored.roster(list);
            assertEquals(new QqGroups.Policy(QqGroups.Mode.OFF, 2), restored.current(54321));
        }
    }
}
