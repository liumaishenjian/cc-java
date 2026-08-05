package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * Settings v1 候选来源的固定优先级类别。
 *
 * @since 0.8.0
 */
public enum SettingsSourceKind {
    /** 编译期可信默认值。 */
    DEFAULTS,
    /** 当前用户的本机来源。 */
    USER,
    /** 工作区内可共享来源。 */
    PROJECT_SHARED,
    /** 工作区内且必须 Git 忽略的本机来源。 */
    PROJECT_LOCAL,
    /** 只存活于当前进程会话的覆盖。 */
    SESSION,
    /** 当前进程显式命令行覆盖。 */
    CLI
}
