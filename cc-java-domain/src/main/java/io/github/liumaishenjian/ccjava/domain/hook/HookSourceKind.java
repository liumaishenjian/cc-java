package io.github.liumaishenjian.ccjava.domain.hook;

/**
 * Hook 声明的来源层级。
 *
 * <p>Hook 使用独立的来源枚举，而不是把 Settings 的内部字段直接暴露给 Core。来源只
 * 用于决定信任 Gate 的默认边界；实际配置解析和优先级合并仍由 CLI 边缘负责。</p>
 *
 * @since 0.9.0
 */
public enum HookSourceKind {
    /** 项目内置、随应用发布的默认 Hook。 */
    DEFAULTS,
    /** 当前用户目录下的本机 Hook。 */
    USER,
    /** 工作区可共享的 Hook 声明。 */
    PROJECT_SHARED,
    /** 工作区本机 Hook 声明，通常应被 Git 忽略。 */
    PROJECT_LOCAL,
    /** 当前进程 Session 覆盖。 */
    SESSION,
    /** 当前命令行显式覆盖。 */
    CLI
}
