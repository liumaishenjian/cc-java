package io.github.liumaishenjian.ccjava.core.subagent;

/**
 * 表示可信 SUB_AGENT_START Hook 在 Scope 物化前阻断任务。
 *
 * @since 0.12.0
 */
public final class ChildTaskStartBlockedException extends RuntimeException {
    /** 创建不携带 Hook 自由文本的固定异常。 */
    public ChildTaskStartBlockedException() {
        super("子任务启动被 Hook 阻断");
    }
}
