package neuralcloud.controlplane.master;

import neuralcloud.controlplane.console.ConsoleServer.Trace;
import neuralcloud.controlplane.memory.MemoryCall;
import neuralcloud.controlplane.orchestration.MasterEpochs;
import neuralcloud.controlplane.orchestration.MasterEpochs.Status;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 正式 Master 的单主干入口。推理传输仍由 Master 负责，Router 和启动探测不取得 Epoch 写租约。 */
public record MasterRuntime(Master model, MasterEpochs epochs, int maxTokens, double temperature) {
    private static String origin(Trace trace) {
        return trace.scope().runId() + ":" + trace.request();
    }

    /** 同一代只有一个主干；具体事项只投影给原受众。历史记录作为引用输入，命令识别只读本轮原文。 */
    private static String withHistory(String history, String prompt) {
        if (history.isEmpty()) return prompt;
        return "以下是本会话以往的工作记录，仅供参考。涉及历史事实时按当前授权重新查证。\n"
                + history + "\n本轮请求：\n" + prompt;
    }

    /** 外部渠道原文及记忆派生的裁决沿原入站审计、当前证据版本重读，避免缓存正文绕过修订或撤权。 */
    private static String topic(Trace trace, String prompt, boolean channelObservation) {
        return !channelObservation && trace.scope().channel().equals("console") ? prompt
                : "处理本会话第 " + trace.request() + " 号入站请求，原文及其受众以渠道记录为准。";
    }

    /** 普通问答使用同一主干。显式记忆修订命令继续由原入口核对原文及授权。 */
    public Master.Completion complete(String prompt, Trace trace, Optional<MemoryCall> memory)
            throws IOException, InterruptedException {
        return epochs.run(trace.scope().conversationId(), origin(trace), topic(trace, prompt, false), history -> {
            boolean revision = memory.isPresent() && prompt.startsWith("/memory revise ");
            String input = revision ? prompt : withHistory(history, prompt);
            var result = memory.isPresent()
                    ? model.completeWithMemory(input, maxTokens, temperature, memory.orElseThrow())
                    : model.complete(input, maxTokens, temperature, List.of());
            boolean finished = result.finishReason().equals("stop") && !result.text().isBlank();
            String record = finished
                    ? (memory.isPresent() ? "Master 已完成本次处理，引用依据须按当前权限重读。" : result.text())
                    : "本轮未取得完整答复，事项仍待处理。";
            return new MasterEpochs.Outcome<>(result, record, finished ? Status.FINISHED : Status.INCOMPLETE, !revision);
        }).value();
    }

    /** 受理意图后才准交给 Router。需要 Worker 的事项在裁决落盘后仍未决，直到对应步骤交回结果。 */
    public MasterEpochs.Result<MasterIntent> intent(String prompt, Trace trace, MemoryCall memory,
                                                    boolean dispatch, boolean replyRequired)
            throws IOException, InterruptedException {
        return epochs.run(trace.scope().conversationId(), origin(trace), topic(trace, prompt, dispatch), history -> {
            int before = memory.eventId();
            var intent = model.submitIntent(withHistory(history, prompt), maxTokens, temperature, memory, replyRequired);
            boolean waiting = dispatch && intent.needsReply();
            String record = waiting ? "Master 已提交回复意图，等待 Chat Worker 回执。" : "Master 已提交本轮意图。";
            // 本轮接收过记忆材料时只投影处理状态，完整意图及证据版本仍随单步检查点落盘。
            if (!dispatch && trace.scope().channel().equals("console") && before == memory.eventId())
                record += "回复目的：" + intent.replyIntent();
            return new MasterEpochs.Outcome<>(intent, record, waiting ? Status.WAITING_FOR_WORKER : Status.FINISHED);
        });
    }

    /** 只有收到并复核 Worker 回执后才结束派单事项。这条记录不声称渠道已经送达。 */
    public void workerReturned(Trace trace, long matter, Map<String, Object> receipt) throws IOException, InterruptedException {
        epochs.resolve(trace.scope().conversationId(), origin(trace), matter, "Chat Worker 已交回回复正文。", receipt);
    }

    /** 操作者显式登记跨轮承诺或提交结项回执。编号来自持久日志，普通聊天不会被猜测成状态命令。 */
    public String matterCommand(String command, Trace trace) throws IOException, InterruptedException {
        var fields = command.split(" ", 3);
        String audience = trace.scope().conversationId();
        if (fields.length == 3 && fields[1].equals("open") && !fields[2].isBlank()) {
            long matter = epochs.open(audience, origin(trace), fields[2]);
            return "已登记未决事项 " + matter;
        }
        if (fields.length == 3 && fields[1].equals("resolve")) {
            var args = fields[2].split(" ", 2);
            if (args.length == 2 && !args[1].isBlank() && args[0].matches("[1-9][0-9]*")) {
                long matter;
                try { matter = Long.parseLong(args[0]); }
                catch (NumberFormatException error) { throw new IOException("事项号须为正的 64 位整数", error); }
                epochs.resolve(audience, origin(trace), matter, args[1]);
                return "已记录事项 " + matter + " 的完成回执";
            }
        }
        throw new IOException("须使用 /epoch open <事项正文> 或 /epoch resolve <事项号> <完成回执>");
    }
}
