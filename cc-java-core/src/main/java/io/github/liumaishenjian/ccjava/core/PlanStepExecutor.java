package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.RunId;
import java.util.Objects;

/**
 * Plan 单步执行端口。
 *
 * <p>实现必须把真实 Tool 调用交给 {@link ToolExecutionPipeline}；该端口不是绕过
 * Permission/Approval 的文件或命令执行入口。只读测试可使用确定性 Fake 实现。</p>
 */
@FunctionalInterface
public interface PlanStepExecutor {
    /** 执行一个已经通过 Plan Gate 的步骤。 */
    PlanStepExecutionResult execute(PlanStep step, CancellationToken cancellationToken);

    /** 生产适配器的统一 Pipeline 执行工厂。 */
    static PlanStepExecutor pipeline(
            ToolExecutionPipeline pipeline, AgentSession session, RunId runId) {
        Objects.requireNonNull(pipeline, "pipeline 不能为空");
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        return (step, token) -> {
            io.github.liumaishenjian.ccjava.domain.ToolCall call =
                    new io.github.liumaishenjian.ccjava.domain.ToolCall(
                            "plan-step-" + step.ordinal(), step.action().toolName(), step.action().arguments());
            io.github.liumaishenjian.ccjava.domain.ToolResult result = pipeline.execute(session, runId,
                    step.ordinal(), call, token);
            if (result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.SUCCESS) {
                return PlanStepExecutionResult.success(step.expectedDigest(), result.content());
            }
            return new PlanStepExecutionResult(
                    result.status() == io.github.liumaishenjian.ccjava.domain.ToolResultStatus.DENIED
                            ? PlanStepExecutionResult.Status.DENIED : PlanStepExecutionResult.Status.FAILURE,
                    step.expectedDigest(), "pipeline tool execution did not succeed");
        };
    }

    /** 校验执行结果，避免实现返回空值或伪造摘要。 */
    static PlanStepExecutionResult requireResult(PlanStepExecutionResult result) {
        return Objects.requireNonNull(result, "Plan step result 不能为空");
    }
}
