package io.github.liumaishenjian.ccjava.domain.instructions;

/**
 * 不携带异常原文、路径或指令正文的发现失败分类。
 *
 * @since 0.8.0
 */
public enum InstructionDiagnosticCode {
    /** 候选无法安全读取或不是普通 UTF-8 文件。 */
    UNREADABLE,
    /** 候选在读取、验证或提交期间未保持已验证身份。 */
    IDENTITY_CHANGED,
    /** 候选超出单文件、总量或层级上限。 */
    LIMIT_EXCEEDED,
    /** 达到最多接受文件数后，后续候选未被加载。 */
    COUNT_LIMIT,
    /** 候选与先前已接受内容同时具有同一 canonical identity 和完整摘要，因而被抑制。 */
    DUPLICATE_SUPPRESSED,
    /** Local 候选没有得到 Git 明确的 ignored 证明。 */
    LOCAL_INSTRUCTIONS_NOT_GITIGNORED,
    /** 发现被取消，候选结果不得发布。 */
    CANCELLED
}
