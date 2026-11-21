package neuralcloud.controlplane.orchestration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Orchestrator {
    public record Scope(String conversation, UUID workflowId) {
        public Scope {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(workflowId, "workflowId");
        }
    }

    public record Step(String id, String worker, String text, List<String> dependsOn) {
        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(text, "text");
            dependsOn = List.copyOf(dependsOn);
        }
    }

    public record Plan(Scope scope, List<Step> steps) {
        public Plan {
            Objects.requireNonNull(scope, "scope");
            steps = List.copyOf(steps);
        }
    }

    public record Receipt<R>(Scope scope, String stepId, R result) {}

    @FunctionalInterface
    public interface Dispatcher<R> {
        // 派发到计划指定的 Worker，收到实际结果后返回。只传入当前步骤声明的前置回执。
        Receipt<R> dispatch(Scope scope, Step step, Map<String, Receipt<R>> dependencies) throws IOException;
    }

    // 按依赖同步派发，返回按步骤标识关联的回执，由调用方解释业务结果。
    public <R> Map<String, Receipt<R>> execute(Plan plan, Dispatcher<R> dispatcher) throws IOException {
        var receipts = new LinkedHashMap<String, Receipt<R>>();
        for (Step step : executionOrder(plan.steps())) {
            var dependencies = new LinkedHashMap<String, Receipt<R>>();
            for (String id : step.dependsOn()) {
                dependencies.put(id, receipts.get(id));
            }
            var receipt = dispatcher.dispatch(plan.scope(), step, Map.copyOf(dependencies));
            if (receipt == null || !plan.scope().equals(receipt.scope())
                    || !step.id().equals(receipt.stepId()) || receipt.result() == null) {
                throw new IOException("步骤 " + step.id() + " 的回执缺失结果或事项、步骤标识不匹配");
            }
            receipts.put(step.id(), receipt);
        }
        return Map.copyOf(receipts);
    }

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
            if (pending.size() == previousSize) {
                throw new IllegalArgumentException("计划依赖须引用已有步骤且无循环，未能排序的步骤：" + pending.keySet());
            }
        }
        return order;
    }
}
