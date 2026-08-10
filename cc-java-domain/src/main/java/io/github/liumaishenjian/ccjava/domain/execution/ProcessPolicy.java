package io.github.liumaishenjian.ccjava.domain.execution;

/**
 * 后代、分离和提权约束。后端不能证明继承约束时不得报告 PROCESS ENFORCED。
 *
 * @param allowDescendants 是否允许受同一隔离继承约束的后代
 * @param allowDetach 是否允许脱离所有权
 * @param allowPrivilegeEscalation 是否允许提权
 * @since 0.13.0
 */
public record ProcessPolicy(
        boolean allowDescendants,
        boolean allowDetach,
        boolean allowPrivilegeEscalation) {
    /**
     * 返回允许受控后代、禁止分离和提权的 S13 保守默认值。
     *
     * @return 保守进程策略
     */
    public static ProcessPolicy restricted() {
        return new ProcessPolicy(true, false, false);
    }
}
