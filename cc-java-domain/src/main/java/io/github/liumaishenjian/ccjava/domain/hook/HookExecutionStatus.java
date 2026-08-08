package io.github.liumaishenjian.ccjava.domain.hook;

/**
 * Hook 调用的可审计终态，不暴露外部进程的原始输出。
 *
 * @since 0.1.0
 */
public enum HookExecutionStatus {
    /** Handler 正常返回。 */
    COMPLETED,
    /** Handler 超过配置的墙钟上限。 */
    TIMED_OUT,
    /** Handler 抛出未分类异常。 */
    FAILED,
    /** Handler 返回的协议无法解析或超出上限。 */
    INVALID_OUTPUT,
    /** 当前 Run 已取消，调用尚未产生可用意见。 */
    CANCELLED,
    /** 绑定未经信任校验，未启动外部动作。 */
    SKIPPED_UNTRUSTED
}
