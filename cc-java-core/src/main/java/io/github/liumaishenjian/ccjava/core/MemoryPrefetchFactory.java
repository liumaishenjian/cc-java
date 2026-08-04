package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.UserMessage;

/**
 * 为当前模型回合尽早启动一次相关记忆预取的 Core Port。
 *
 * <p>实现只能安排由自身拥有资源管理的有界召回工作并立即返回句柄；不得在调用线程执行文件 I/O、
 * 等待锁或等待召回结果。文件路径、Repository 身份和 Executor 生命周期属于 D2 Adapter，不进入本
 * Port。Runtime 会在该回合唯一消费点后非阻塞关闭返回的句柄。</p>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface MemoryPrefetchFactory {

    /**
     * 从当前有界 UserMessage 启动一个全新的回合级预取。
     *
     * @param currentUserMessage 当前 Run 的用户消息，不是完整 Transcript
     * @param cancellationToken 召回工作应协作观察的 Run 取消令牌
     * @return 不得复用到其他回合的 ready-only 句柄
     */
    MemoryPrefetch start(
            UserMessage currentUserMessage,
            CancellationToken cancellationToken);
}
