package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.CheckpointTarget;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.util.Optional;

/**
 * 由统一 Tool Registry 注册、并且只能经 Pipeline 执行的 Tool 契约。
 *
 * <p>实现必须保持 Definition 稳定，并在 {@link #validate(JsonObject)} 中
 * 完成确定性参数检查。不能在参数校验阶段产生环境副作用。</p>
 *
 * @since 0.1.0
 */
public interface AgentTool {

    /**
     * 返回稳定 Tool Definition。
     *
     * @return 不随调用变化的元数据
     */
    ToolDefinition definition();

    /**
     * 在权限判断和执行前校验模型参数。
     *
     * @param arguments 模型提供的不可变参数
     * @return 确定性校验结果
     */
    default ToolValidationResult validate(JsonObject arguments) {
        return ToolValidationResult.validResult();
    }

    /**
     * 在 Permission 最终允许后声明可验证的普通文件 Checkpoint 目标。
     *
     * <p>默认没有可恢复文件目标。声明 {@code WRITE_WORKSPACE} 的实现必须显式重写该方法并
     * 重用自身 WorkspaceGuard 规则；否则生产 Checkpoint Pipeline Fail Closed，不能用伪造路径
     * 掩盖尚未接入恢复契约的写 Tool。</p>
     *
     * @param invocation 已通过参数校验和权限判断的调用
     * @return 最多一个普通文件目标
     * @throws Exception 目标状态无法安全验证时
     */
    default Optional<CheckpointTarget> checkpointTarget(ToolInvocation invocation) throws Exception {
        return Optional.empty();
    }

    /**
     * 执行已经通过校验和权限决策的调用。
     *
     * @param invocation 调用上下文
     * @return 尚未绑定 Call ID 的业务结果
     * @throws Exception Tool 实现无法返回结构化业务错误时抛出
     */
    ToolExecutionOutcome execute(ToolInvocation invocation) throws Exception;
}
