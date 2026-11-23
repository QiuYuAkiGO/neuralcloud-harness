package neuralcloud.controlplane.channel;

import com.google.gson.JsonParser;
import org.junit.Test;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OpenQqChannelTest {
    /** 纯图与图文交错均从图片段取得索引键；私聊白名单、群点名与控制命令保留原来的身份边界。 */
    @Test
    public void acceptsOrderedImagesOnlyInAuthorizedMessages() {
        var event = JsonParser.parseString("""
                {"post_type":"message","message_type":"group","self_id":12345,"group_id":55555,
                 "user_id":54321,"message_id":68,"message":[{"type":"at","data":{"qq":"12345"}},
                 {"type":"image","data":{"file":"a.png","file_id":"one","url":"https://unused.invalid"}},
                 {"type":"text","data":{"text":"对比"}},
                 {"type":"image","data":{"file":"two"}}]}
                """).getAsJsonObject();
        var mixed = OpenQqChannel.incoming(event,12345,54321,55555,false);
        assertEquals("【图片1】对比【图片2】", mixed.text());
        assertEquals(List.of("one","two"), mixed.imageKeys());
        var segments = event.getAsJsonArray("message");
        segments.remove(0);
        assertNull(OpenQqChannel.incoming(event,12345,54321,55555,false));
        event.addProperty("message_type","private");
        event.addProperty("sub_type","friend");
        segments.remove(2);
        segments.remove(1);
        assertEquals("【图片1】", OpenQqChannel.incoming(event,12345,54321).text());
        assertNull(OpenQqChannel.incoming(event,12345,54322,0,true));
    }

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
