package io.github.liumaishenjian.ccjava.domain.hook;

/**
 * Hook 执行失败时的独立策略。
 *
 * <p>策略属于 Hook 绑定而不是全局异常处理器，因此一个诊断 Hook 的故障不会
 * 意外改变写入 Tool 的安全边界；决策点上的 {@link #FAIL_CLOSED} 才能把故障
 * 变成阻断。</p>
 *
 * @since 0.1.0
 */
public enum HookFailurePolicy {
    /** 失败只记录为非阻断结果并继续。 */
    FAIL_OPEN,
    /** 失败转换成 BLOCK（Permission 请求转换成 DENY）。 */
    FAIL_CLOSED,
    /** 该绑定只允许观察，返回的阻断意见也会被降级。 */
    OBSERVE_ONLY
}
