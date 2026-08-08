/**
 * Core 内的 Hook 匹配、并发执行和确定性聚合协调器。
 *
 * <p>该包与只读的 {@code LifecycleDispatcher} 分离：只有显式经过
 * {@link io.github.liumaishenjian.ccjava.core.hook.HookCoordinator} 的决策点才可能
 * 被 Hook 阻断，所有实际 Tool 副作用仍必须回到统一 Pipeline。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.core.hook;
