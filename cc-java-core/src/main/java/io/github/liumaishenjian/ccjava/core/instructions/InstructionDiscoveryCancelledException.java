package io.github.liumaishenjian.ccjava.core.instructions;

/**
 * 表示指令发现被取消且调用者不得发布本轮任何候选结果。
 *
 * @since 0.8.0
 */
public final class InstructionDiscoveryCancelledException extends RuntimeException {

    /** 创建不含外部细节的取消异常。 */
    public InstructionDiscoveryCancelledException() {
        super("instruction discovery cancelled");
    }
}
