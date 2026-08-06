package io.github.liumaishenjian.ccjava.domain;

/**
 * 控制独立模型诊断平面的本机记录强度。
 *
 * <p>该模式不改变模型请求、重试、Run 终态或用户可见摘要。{@link #OFF}
 * 是默认且应保持零记录开销；{@link #SAFE} 仅允许失败；{@link #VERBOSE}
 * 仍只能使用封闭字段。</p>
 *
 * @since 0.1.0
 */
public enum ModelDiagnosticMode {
    /** 完全关闭诊断平面。 */
    OFF,
    /** 只记录脱敏失败事件。 */
    SAFE,
    /** 允许封闭的失败及生命周期事件。 */
    VERBOSE
}
