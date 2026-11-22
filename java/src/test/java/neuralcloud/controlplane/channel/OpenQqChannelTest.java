package neuralcloud.controlplane.channel;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OpenQqChannelTest {
    @Test
    public void acceptsOnlyPlainPrivateMessagesFromConfiguredUser() {
        var event = JsonParser.parseString("""
                {"post_type":"message","message_type":"private","sub_type":"other",
                 "self_id":12345,"user_id":54321,"message_id":67,
                 "message":[{"type":"text","data":{"text":"检查"}},{"type":"text","data":{"text":"服务器状态。"}}]}
                """).getAsJsonObject();
        assertEquals(
                new OpenQqChannel.Incoming(67, "检查服务器状态。", 0, 54321),
                OpenQqChannel.incoming(event, 12345, 54321));
        assertNull(OpenQqChannel.incoming(event, 12345, 54322));
        assertNull(OpenQqChannel.incoming(event, 12346, 54321));

        event.addProperty("sub_type", "group");
        assertNull(OpenQqChannel.incoming(event, 12345, 54321));
        event.addProperty("sub_type", "friend");
        event.addProperty("message_type", "group");
        assertNull(OpenQqChannel.incoming(event, 12345, 54321));
        event.addProperty("message_type", "private");

        event.getAsJsonArray("message").get(1).getAsJsonObject().addProperty("type", "image");
        assertNull(OpenQqChannel.incoming(event, 12345, 54321));
        event.getAsJsonArray("message").get(1).getAsJsonObject().addProperty("type", "text");
        event.addProperty("message_id", "67");
        assertNull(OpenQqChannel.incoming(event, 12345, 54321));
    }
}
