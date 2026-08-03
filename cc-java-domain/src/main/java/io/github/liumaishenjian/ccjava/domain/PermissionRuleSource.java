package io.github.liumaishenjian.ccjava.domain;

/**
 * 标识 Permission Rule 的可信装载来源。
 *
 * <p>{@link #STARTUP} 由 Composition Root 在启动时显式注入；
 * {@link #SESSION} 只存在当前内存 Session。S05 不从项目文件、模型文本或
 * Tool 参数加载规则，也不实现 S08/S13 的持久分层配置。</p>
 *
 * @since 0.5.0
 */
public enum PermissionRuleSource {

    /** 进程启动时由可信应用代码注入。 */
    STARTUP,

    /** 用户审批后写入当前内存 Session 的范围化 Grant。 */
    SESSION
}
