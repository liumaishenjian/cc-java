package io.github.liumaishenjian.ccjava.core.hook;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;

/**
 * Core Hook Handler 端口。
 *
 * <p>Handler 只处理已脱敏的 Hook 请求并返回结构化意见；它不能取得
 * {@code AgentRuntime}、绕过 Permission 或直接执行模型 Tool。Command/HTTP 等
 * 外部实现必须在 Core 之外通过此端口接入。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface HookHandler {

    /**
     * 返回项目内稳定的 Handler ID。
     *
     * @return 非空 Handler ID
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * 执行一次已匹配的 Hook。
     *
     * @param invocation 已脱敏的生命周期请求
     * @param cancellationToken 当前 Run 的取消信号
     * @return 不含原始外部输出的结构化结果
     */
    HookExecutionResult execute(HookInvocation invocation, CancellationToken cancellationToken);
}
