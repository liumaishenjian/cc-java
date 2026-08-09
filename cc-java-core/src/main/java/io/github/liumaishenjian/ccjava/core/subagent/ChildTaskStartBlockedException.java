package io.github.liumaishenjian.ccjava.core.subagent;

/**
 * 表示可信 SUB_AGENT_START Hook 在 Scope 物化前阻断任务。
 *
 * @since 0.12.0
 */
public final class ChildTaskStartBlockedException extends RuntimeException {
    public ChildTaskStartBlockedException() {
        super("子任务启动被 Hook 阻断");
    }
}
