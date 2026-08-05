package io.github.liumaishenjian.ccjava.domain.instructions;

/**
 * 指令候选的受限来源类别。
 *
 * <p>该枚举只说明层级来源，不能表达文件路径、信任级别或权限能力。</p>
 *
 * @since 0.8.0
 */
public enum InstructionSourceKind {
    /** 用户固定根中的全局指令。 */
    USER,
    /** Workspace 根中的项目指令。 */
    PROJECT,
    /** 已验证目标祖先目录中的指令。 */
    DIRECTORY,
    /** 预留的本地项目指令来源；本切片不加载它。 */
    LOCAL
}
