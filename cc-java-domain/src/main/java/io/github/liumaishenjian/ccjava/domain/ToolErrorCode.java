package io.github.liumaishenjian.ccjava.domain;

/**
 * 模型可以据此采取纠正动作的稳定 Tool 错误分类。
 *
 * @since 0.1.0
 */
public enum ToolErrorCode {

    /** Registry 中不存在请求的 Tool。 */
    UNKNOWN_TOOL,

    /** Tool 参数不满足确定性校验。 */
    INVALID_ARGUMENTS,

    /** Tool 实现执行失败。 */
    EXECUTION_FAILED,

    /** 权限或人工审批拒绝调用。 */
    PERMISSION_DENIED,

    /** Tool 实现返回了违反协议的结果。 */
    RESULT_PROTOCOL_VIOLATION,

    /** Runtime 无法归类的内部错误。 */
    INTERNAL_ERROR
}
