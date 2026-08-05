package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;

/**
 * 从 Adapter 已验证的逻辑候选加载有界指令内容的 Core Port。
 *
 * <p>实现负责真实路径、普通文件、UTF-8、identity 稳定性和字节限制；Core 只协调确定性顺序。</p>
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface InstructionLoader {

    /**
     * 加载一个候选。
     *
     * @param candidate 不含物理路径的逻辑候选
     * @param cancellationToken 当前刷新取消令牌
     * @return 成功值或安全失败分类
     */
    InstructionLoadResult load(InstructionCandidate candidate, CancellationToken cancellationToken);
}
