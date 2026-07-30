package io.github.liumaishenjian.ccjava.domain;

/**
 * S04 固定权限策略所处的运行模式。
 *
 * <p>{@link #DEFAULT} 允许只读操作，并把 Workspace 写入和本地进程交给单次审批；
 * {@link #PLAN} 只允许读取。该枚举不包含 S05 的可配置规则、Session Allow 或
 * Hard Denial 来源。</p>
 *
 * @since 0.1.0
 */
public enum PermissionMode {

    /** 普通交互模式，副作用操作必须经过审批。 */
    DEFAULT,

    /** 固定安全规划模式，拒绝写入、进程、网络和系统副作用。 */
    PLAN
}
