package neuralcloud.controlplane.memory;

import static neuralcloud.controlplane.memory.ResidentV2.*;

import com.google.gson.*;

import java.io.IOException;
import java.util.*;

/** 受信宿主的整理读边界。会话、作者和角色均由宿主根据启动握手生成。 */
public final class DreamingMemory {
    private final ResidentV2 memory;
    private static final List<String> POSITION =
            List.of("stream_id", "policy_version", "after", "through", "lane");

    DreamingMemory(ResidentV2 memory) {
        this.memory = memory;
    }

    public String character() {
        return memory.character();
    }

    private JsonObject context(String role) {
        String home = "dreaming:" + character();
        return object(
                "character_id",
                character(),
                "conversation_id",
                home,
                "session_id",
                home,
                "actor_id",
                "DREAMING",
                "role",
                role);
    }

    public JsonObject batch(Collection<String> scopes, int limit)
            throws IOException, InterruptedException {
        // 新宿主主动领取可核实的已授权群聊旧页，Memory 在独立的历史进度上签发位置。
        var batch =
                memory.request(
                        "v2/dreaming/batch",
                        object(
                                "context",
                                context("ORCHESTRATOR"),
                                "scopes",
                                scopes,
                                "limit",
                                limit,
                                "include_group_history",
                                true));
        if (!memory.stream().equals(string(batch, "stream_id"))
                || integer(batch, "through", 0) < integer(batch, "after", 0)
                || !(batch.get("profiles") instanceof JsonArray)
                || !(batch.get("evidence") instanceof JsonArray)
                || (batch.has("lane") && !Set.of("identity_backfill", "group_history").contains(string(batch, "lane"))))
            throw new IOException("Dreaming 取证响应的流、材料或 lane 无效");
        integer(batch, "policy_version", 1);
        return batch;
    }

    public JsonArray profiles() throws IOException, InterruptedException {
        var answer =
                memory.request("v2/dreaming/profiles", object("context", context("ORCHESTRATOR")));
        if (!(answer.get("profiles") instanceof JsonArray profiles))
            throw new IOException("Dreaming 缺少档案列表");
        return profiles;
    }

    /** 旧调用方只提交认识，兼容既有命令入口。 */
    public JsonObject command(JsonObject batch, JsonArray changes) {
        return command(batch, changes, new JsonArray());
    }

    /** 两类操作随同一个事件键登记。实时与历史回扫可有相同数字位置，回执及重发都须核对 lane。 */
    public JsonObject command(JsonObject batch, JsonArray changes, JsonArray claims) {
        String lane = batch.has("lane") ? batch.get("lane").getAsString() + ":" : "";
        var command =
                object(
                        "context",
                        context("ROUTER"),
                        "source_instance",
                        SOURCE,
                        "event_id",
                        "dreaming:" + lane + UUID.randomUUID(),
                        "changes",
                        changes,
                        "name_claims",
                        claims);
        for (String key : POSITION)
            if (batch.has(key)) command.add(key, batch.get(key));
        return command;
    }

    public boolean sameBatch(JsonObject command, JsonObject batch) {
        return POSITION.stream().allMatch(k -> Objects.equals(command.get(k), batch.get(k)));
    }

    /** 版本冲突和字段拒绝都是 Memory 已确定未提交的答复，原证据仍可由模型重新整理。 */
    public static boolean rejectedProposal(IOException error) {
        return error instanceof ResidentV2.SourceChanged
                || (error instanceof ResidentMemory.ServiceError service
                        && service.status == 422 && "INVALID_FIELD".equals(service.code));
    }

    public Optional<JsonObject> receipt(JsonObject command)
            throws IOException, InterruptedException {
        if (!memory.stream().equals(string(command, "stream_id")))
            throw new IOException("待确认的 Dreaming 命令属于另一 Memory 流，无法据当前库清除原命令");
        var reply =
                memory.request(
                        "v2/dreaming/receipt",
                        object(
                                "context",
                                context("ORCHESTRATOR"),
                                "source_instance",
                                SOURCE,
                                "event_id",
                                string(command, "event_id")));
        return "NOT_FOUND".equals(string(reply, "status")) ? Optional.empty() : Optional.of(reply);
    }

    /** 确认精确到事件键和覆盖位置，禁止把新流的同名回执用于旧命令。 */
    public void checkReceipt(JsonObject command, JsonObject receipt) throws IOException {
        if (!"DURABLE".equals(string(receipt, "status"))
                || !Objects.equals(command.get("event_id"), receipt.get("event_id"))
                || !sameBatch(command, receipt)) throw new IOException("Dreaming 回执身份或覆盖区间不符");
    }
}
