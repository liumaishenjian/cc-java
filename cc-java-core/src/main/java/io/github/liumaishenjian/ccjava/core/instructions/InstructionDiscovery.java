package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstructions;

/**
 * 从经过 Adapter 验证的候选构造一次原子指令投影的用例契约。
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface InstructionDiscovery {

    /**
     * 完整发现并构造候选投影。
     *
     * @param request 已验证候选
     * @param cancellationToken 当前操作取消令牌
     * @return 完整结果；取消时不返回 partial publish
     */
    ResolvedInstructions discover(
            InstructionDiscoveryRequest request,
            CancellationToken cancellationToken);
}
