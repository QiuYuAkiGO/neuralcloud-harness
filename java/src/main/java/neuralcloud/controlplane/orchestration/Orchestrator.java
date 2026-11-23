package neuralcloud.controlplane.orchestration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 多步骤协作计划的确定性同步编排器。
 * 职责：根据步骤之间的显式依赖（DAG）先完成全图合法性与拓扑排序校验，
 * 再严格按依赖顺序同步派发给对应的 Worker，并校验每一步返回的回执归属（{@code scope} 与 {@code stepId}）
 * 防止串会话或串步骤误用结果。编排器本身不解释业务语义，仅负责依赖推进与回执归集。
 */
public final class Orchestrator {
    /** 一次编排执行的隔离边界，由会话标识与唯一工作流 UUID 组成。 */
    public record Scope(String conversation, UUID workflowId) {
        public Scope {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(workflowId, "workflowId");
        }
    }

    /** 计划中的单个可执行步骤，声明目标 Worker、指令文本及必须先完成的依赖步骤标识列表。 */
    public record Step(String id, String worker, String text, List<String> dependsOn) {
        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(text, "text");
            dependsOn = List.copyOf(dependsOn);
        }
    }

    /** 绑定执行作用域的不可变多步计划。 */
    public record Plan(Scope scope, List<Step> steps) {
        public Plan {
            Objects.requireNonNull(scope, "scope");
            steps = List.copyOf(steps);
        }
    }

    /** 单步执行完成后的强类型回执，必须回带相同的 {@code scope} 与 {@code stepId} 以供边界核验。 */
    public record Receipt<R>(Scope scope, String stepId, R result) {}

    /** 步骤派发回调：由上层控制面将抽象步骤路由到具体 Worker 并阻塞等待真实回执。 */
    @FunctionalInterface
    public interface Dispatcher<R> {
        /** 派发到计划指定的 Worker，收到实际结果后返回；{@code dependencies} 仅包含当前步骤显式声明的前置回执。 */
        Receipt<R> dispatch(Scope scope, Step step, Map<String, Receipt<R>> dependencies) throws IOException;
    }

    /**
     * 按依赖拓扑顺序同步执行整个计划，返回按步骤标识索引的不可变回执映射表。
     * 每步派发前只传递其 {@code dependsOn} 声明的前置回执（最小暴露原则）；
     * 回执缺失结果或作用域/步骤标识不匹配时立即中止整条工作流。
     */
    public <R> Map<String, Receipt<R>> execute(Plan plan, Dispatcher<R> dispatcher) throws IOException {
        var receipts = new LinkedHashMap<String, Receipt<R>>();
        for (Step step : executionOrder(plan.steps())) {
            var dependencies = new LinkedHashMap<String, Receipt<R>>();
            for (String id : step.dependsOn()) {
                dependencies.put(id, receipts.get(id));
            }
            var receipt = dispatcher.dispatch(plan.scope(), step, Map.copyOf(dependencies));
            // 严格核验回执归属，杜绝异步错乱或伪造空结果混入后续步骤的依赖上下文。
            if (receipt == null || !plan.scope().equals(receipt.scope())
                    || !step.id().equals(receipt.stepId()) || receipt.result() == null) {
                throw new IOException("步骤 " + step.id() + " 的回执缺失结果或事项、步骤标识不匹配");
            }
            receipts.put(step.id(), receipt);
        }
        return Map.copyOf(receipts);
    }

    /**
     * 校验步骤标识唯一性及依赖图无环性，并计算确定性的执行顺序（保持同层步骤的声明顺序）。
     * 在任何 Worker 实际产生副作用之前完成全图校验，避免坏计划执行到一半才被拒绝。
     */
    private static List<Step> executionOrder(List<Step> steps) {
        var pending = new LinkedHashMap<String, Step>();
        for (Step step : steps) {
            if (step.id().isBlank() || step.worker().isBlank() || pending.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("计划步骤须有唯一非空标识和非空目标 Worker");
            }
        }

        // 在任何 Worker 动作前验证整个依赖关系，避免错误计划执行到一半才被拒绝。
        var order = new ArrayList<Step>(steps.size());
        var orderedIds = new HashSet<String>();
        while (!pending.isEmpty()) {
            int previousSize = pending.size();
            var iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                Step step = iterator.next();
                if (orderedIds.containsAll(step.dependsOn())) {
                    order.add(step);
                    orderedIds.add(step.id());
                    iterator.remove();
                }
            }
            // 一整轮扫描没有任何步骤满足前置条件，说明存在环依赖或引用了不存在的步骤 ID。
            if (pending.size() == previousSize) {
                throw new IllegalArgumentException("计划依赖须引用已有步骤且无循环，未能排序的步骤：" + pending.keySet());
            }
        }
        return order;
    }
}
