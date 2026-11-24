package neuralcloud.controlplane.router;

import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.master.MasterIntent;
import neuralcloud.controlplane.memory.ResidentMemory;
import neuralcloud.controlplane.memory.ResidentMemory.Kind;
import neuralcloud.controlplane.memory.ResidentV2;
import neuralcloud.controlplane.memory.ObserveQueue;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Router 统一的记忆写入与交付复核边界。
 * 控制面采用“读写分离”架构：模型与 Worker 节点只能按轮次读取记忆，所有落盘写入均归口至此，
 * 保证入站观察（{@link Kind#OBSERVATION}）、已送达角色回复（{@link Kind#SELF_EXPRESSION}）
 * 以及工具阶段（{@code TOOL_ATTEMPT}/{@code TOOL_RESULT}）均以原始审计事件序号作为幂等键，并同步写入审计链。
 */
public final class RouterMemoryWriter {
    private final AuditLog audit;
    private final ResidentMemory memory;

    public RouterMemoryWriter(AuditLog audit, ResidentMemory memory) {
        this.audit = audit;
        this.memory = memory;
    }

    /** 底层记忆服务是否支持 Memory v2 的来源见证（attestation）与受众复核。 */
    public boolean supportsAttestation() {
        return memory instanceof neuralcloud.controlplane.memory.ResidentV2;
    }

    /** 终审或显式意图中的新印象沿同一受众入账。无新印象时不创建任何记忆。 */
    public int rememberImpression(Trace trace, int parent, MasterIntent intent) throws IOException, InterruptedException {
        if (intent.impression().isEmpty()) return parent;
        int writing = trace.write(parent, "router", "router_memory_write",
                Map.of("kind", "impression", "source_refs", intent.evidence()));
        var source = audit.event(trace.scope(), trace.request());
        try {
            var receipt = ((ResidentV2) memory).rememberImpression(trace.scope(),
                    trace.scope().runId() + ":" + trace.request() + ":impression",
                    Instant.parse(source.recordedAt()), intent.impression(), intent.evidence());
            return trace.write(writing, "memory", "memory_durable", Map.of("kind", "impression",
                    "recordId", receipt.recordId(), "revision", receipt.revision(), "sequence", receipt.sequence()));
        } catch (IOException | InterruptedException error) {
            trace.write(writing, "memory", "memory_write_failed", Map.of("kind", "impression", "detail", error.toString()));
            throw error;
        }
    }

    /** P3 使用同一 Router 写边界。命令在群策略锁内发起，失联按原键对账。 */
    public com.google.gson.JsonObject observationCandidate(
            Trace.Scope scope,
            String key,
            Instant at,
            neuralcloud.controlplane.master.MasterIntent.Impression submission) {
        return ((ResidentV2) memory)
                .observationCandidate(
                        scope, key, at, submission.impression(), submission.evidence());
    }

    public ResidentV2.PendingReceipt sendObservation(com.google.gson.JsonObject command) {
        return ((ResidentV2) memory).sendObservation(command);
    }

    public java.util.Optional<com.google.gson.JsonObject> observationReceipt(String key)
            throws IOException, InterruptedException {
        return ((ResidentV2) memory).observationReceipt(key);
    }

    /** 是否已启用渠道原作者授权机制（要求 Memory v2 且配置了独立的授权令牌）。 */
    public boolean authorGrantsEnabled() {
        return memory instanceof ResidentV2 v2 && v2.authorGrantsEnabled();
    }

    /**
     * 在渠道向外发送包含跨会话共享来源的回复前，向 Memory v2 二次复核引用的所有记录是否仍处于有效授权期内，
     * 防止授权刚被撤回或过期时继续外泄历史内容。
     */
    public void verifyDelivery(Trace trace) throws IOException, InterruptedException {
        if (!trace.sharedSources().isEmpty() && memory instanceof ResidentV2 v2)
            v2.verifyDelivery(trace);
    }

    /** 将渠道适配器收到的已认证原作者私聊授权/撤回命令透传给 Memory v2 处理。 */
    public com.google.gson.JsonObject authorDecision(Trace trace, long messageId, String command)
            throws IOException, InterruptedException {
        if (!(memory instanceof ResidentV2 v2)) throw new IOException("此渠道未启用原作者授权");
        return v2.authorDecision(trace.scope(), messageId, command);
    }

    /**
     * 持久化用户入站发言（{@link Kind#OBSERVATION}）。
     * 该写入必须在模型处理本轮消息前成功完成；若入库失败会向上抛出异常中止本轮，防止模型基于未持久化的输入作答。
     */
    public int rememberInbound(Trace trace, int accepted, String text) throws IOException, InterruptedException {
        int writing = request(trace, accepted, trace.request(), Kind.OBSERVATION);
        return persist(trace, writing, trace.request(), Kind.OBSERVATION, text);
    }

    /**
     * 持久化渠道已确认送达的角色回复（{@link Kind#SELF_EXPRESSION}）。
     * 业务语义：只有渠道确认发出（如 HTTP 响应已写出或 OpenQQ 返回发送回执）后才调用此方法；
     * 若此时记忆服务异常，仅在审计链和标准错误中记账，绝不抛出打断或诱发渠道重发，避免用户收到重复消息。
     */
    public void rememberDelivered(Trace trace, int delivered, String text) throws IOException {
        int writing = request(trace, delivered, delivered, Kind.SELF_EXPRESSION);
        try {
            persist(trace, writing, delivered, Kind.SELF_EXPRESSION, text);
        } catch (InterruptedException | IOException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 渠道已经确认送达。写入失败单独记账，渠道不得因此重发。
            error.printStackTrace(System.err);
        }
    }

    /**
     * 提交宿主执行器产生的工具生命周期记录（仅 Memory v2 生效；v1 下直接原样返回父事件号）。
     * {@code completed=false} 记录发起动作前的尝试（{@code TOOL_ATTEMPT}），{@code completed=true} 记录采样回执（{@code TOOL_RESULT}），
     * 二者通过同一个 {@code actionId} 配对。模型自由生成的叙述严禁进入此写路径。
     */
    public int rememberTool(
            Trace trace, int parent, String actionId, boolean completed, String content)
            throws IOException, InterruptedException {
        if (!(memory instanceof ResidentV2 v2)) return parent;
        String kind = completed ? "TOOL_RESULT" : "TOOL_ATTEMPT";
        int writing =
                trace.write(
                        parent,
                        "router",
                        "router_memory_write",
                        Map.of("kind", kind, "actionId", actionId));
        var source = audit.event(trace.scope(), writing);
        try {
            var receipt =
                    v2.ingestTool(
                            trace.scope(),
                            trace.scope().runId() + ":" + writing,
                            Instant.parse(source.recordedAt()),
                            completed,
                            actionId,
                            content);
            return trace.write(
                    writing,
                    "memory",
                    "memory_durable",
                    Map.of(
                            "kind",
                            kind,
                            "actionId",
                            actionId,
                            "recordId",
                            receipt.recordId(),
                            "revision",
                            receipt.revision(),
                            "sequence",
                            receipt.sequence()));
        } catch (IOException | InterruptedException error) {
            trace.write(
                    writing,
                    "memory",
                    "memory_write_failed",
                    Map.of("kind", kind, "detail", error.toString()));
            throw error;
        }
    }

    /** 在审计链中先登记一条 {@code router_memory_write} 意图事件，再发起实际远端写入。 */
    private static int request(Trace trace, int parent, int source, Kind kind) throws IOException {
        return trace.write(parent, "router", "router_memory_write", Map.of("kind", kind.name(), "sourceEventId", source));
    }

    /**
     * 核心落盘执行方法：按原始审计事件构造全局幂等键（{@code runId:sourceId}）和时间戳，
     * 对 QQ 渠道提取原始 {@code messageId}，对 Memory v2 透传渠道观察元数据或跨会话引用清单（{@code source_refs}），
     * 成功时写出 {@code memory_durable} 回执事件，失败时记录 {@code memory_write_failed} 并重抛。
     */
    private int persist(Trace trace, int writing, int sourceId, Kind kind, String text)
            throws IOException, InterruptedException {
        var scope = trace.scope();
        var source = audit.event(scope, sourceId);
        // QQ 私聊与群聊事件均携带平台侧 messageId，用于与远端渠道记录对齐去重。
        String messageId =
                scope.qq().isPresent() || scope.group().isPresent()
                        ? source.data().getAsJsonObject().get("messageId").getAsString()
                        : "";
        ResidentMemory.Receipt receipt;
        try {
            String event = scope.runId() + ":" + sourceId;
            if (memory instanceof neuralcloud.controlplane.memory.ResidentV2 v2) {
                var origin = source.data().getAsJsonObject().getAsJsonObject("observation");
                // 已送达回复若引用了跨会话受众材料，将经过交付复核的 source_refs 随回执一并上报给 v2 溯源。
                if (kind == Kind.SELF_EXPRESSION
                        && source.data().getAsJsonObject().has("source_refs")) {
                    origin = new com.google.gson.JsonObject();
                    origin.add("source_refs", source.data().getAsJsonObject().get("source_refs"));
                }
                var group = scope.group().orElse(null);
                var observed =
                        kind == Kind.OBSERVATION && group != null && group.observeRevision() > 0
                                ? new ObserveQueue.Origin(
                                        group.accountId(),
                                        group.groupId(),
                                        group.observeRevision(),
                                        messageId,
                                        scope.runId(),
                                        sourceId)
                                : null;
                receipt =
                        v2.ingestEvent(
                                scope,
                                event,
                                Instant.parse(source.recordedAt()),
                                kind,
                                text,
                                messageId,
                                origin, observed);
            } else {
                receipt =
                        memory.ingest(
                                scope,
                                event,
                                Instant.parse(source.recordedAt()),
                                kind,
                    text, messageId);
            }
        } catch (IOException | InterruptedException error) {
            trace.write(writing, "memory", "memory_write_failed", Map.of("kind", kind.name(), "detail", error.toString()));
            throw error;
        }
        var durable = new java.util.LinkedHashMap<String, Object>(Map.of("sessionId", scope.sessionId(), "kind", kind.name(),
                "characterId", memory.character(), "recordId", receipt.recordId(), "revision", receipt.revision(),
                "sequence", receipt.sequence()));
        if (memory instanceof ResidentV2 v2) durable.put("stream_id", v2.stream());
        return trace.write(writing, "memory", "memory_durable", durable);
    }
}
