package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;

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
     * 执行已经通过校验和权限决策的调用。
     *
     * @param invocation 调用上下文
     * @return 尚未绑定 Call ID 的业务结果
     * @throws Exception Tool 实现无法返回结构化业务错误时抛出
     */
    ToolExecutionOutcome execute(ToolInvocation invocation) throws Exception;
}
