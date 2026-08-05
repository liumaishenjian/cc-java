package io.github.liumaishenjian.ccjava.domain.instructions;

/**
 * 一条指令在投影中的逻辑作用域。
 *
 * @since 0.8.0
 */
public enum InstructionScopeKind {
    /** 对当前用户下的全部 Workspace 生效。 */
    USER_GLOBAL,
    /** 仅对当前 Workspace 生效。 */
    WORKSPACE,
    /** 对某个已验证目录及其子树生效。 */
    DIRECTORY_SUBTREE
}
