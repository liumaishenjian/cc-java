package io.github.liumaishenjian.ccjava.domain.hook;

/**
 * 单个 Hook 对当前生命周期点的意见。
 *
 * <p>{@link #DENY} 与 {@link #BLOCK} 都是阻断意见，差异只用于区分 Permission
 * 与一般决策点；聚合时二者均优先于 {@link #ALLOW} 和 {@link #CONTINUE}。</p>
 *
 * @since 0.1.0
 */
public enum HookDisposition {
    /** Hook 不改变当前决策。 */
    CONTINUE,
    /** Permission Hook 明确允许。 */
    ALLOW,
    /** Permission Hook 明确拒绝。 */
    DENY,
    /** 一般决策点明确阻断。 */
    BLOCK
}
