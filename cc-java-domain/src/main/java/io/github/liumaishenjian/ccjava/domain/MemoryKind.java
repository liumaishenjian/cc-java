package io.github.liumaishenjian.ccjava.domain;

/**
 * 项目文件记忆的受限语义分类。
 *
 * <p>分类只影响目录与召回语义，不能提升 Permission、改变 Session 事实或授权副作用。</p>
 *
 * @since 0.7.0
 */
public enum MemoryKind {

    /** 用户长期工作方式相关且经提供或确认的稳定信息。 */
    USER_PROFILE,

    /** 协作纠正、偏好及其原因和应用方式。 */
    WORKING_GUIDANCE,

    /** 无法仅从仓库或 Git 历史可靠推导的持续项目状态。 */
    PROJECT_STATE,

    /** 外部文档、Issue 或 Dashboard 的用途指针。 */
    REFERENCE_POINTER
}
