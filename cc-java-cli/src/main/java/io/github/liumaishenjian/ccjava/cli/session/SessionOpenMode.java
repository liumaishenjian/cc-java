package io.github.liumaishenjian.ccjava.cli.session;

/**
 * Java Headless 启动时选择持久 Session 的模式。
 *
 * @since 0.6.0
 */
public enum SessionOpenMode {
    /** 创建全新 Session。 */
    CREATE,
    /** 继续同一 Workspace 最近的可恢复 Session。 */
    CONTINUE,
    /** 按 ID 恢复同一 Session。 */
    RESUME,
    /** 从指定 Session 创建隔离的新 Session ID。 */
    FORK,
    /** 只读解析，不获得 Runtime/Tool 执行能力。 */
    INSPECT
}
