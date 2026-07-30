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

    /** 模型传入了绝对、UNC、drive-relative 或其他禁止的路径。 */
    INVALID_PATH,

    /** 规范化后的逻辑路径越过 Workspace。 */
    WORKSPACE_BOUNDARY_VIOLATION,

    /** 请求的路径不存在。 */
    PATH_NOT_FOUND,

    /** 请求路径的文件类型不满足 Tool 契约。 */
    PATH_TYPE_MISMATCH,

    /** Symlink 或 Junction 的真实目标逃出 Workspace。 */
    LINK_ESCAPE,

    /** 固定安全策略拒绝访问敏感路径。 */
    SENSITIVE_PATH,

    /** 普通文本文件超过当前 Stage 的读取上限。 */
    FILE_TOO_LARGE,

    /** 文件不是受支持的严格 UTF-8 文本。 */
    UNSUPPORTED_ENCODING,

    /** Workspace 不是 Git 仓库。 */
    NOT_A_GIT_REPOSITORY,

    /** 当前平台找不到可用 Git 程序。 */
    GIT_UNAVAILABLE,

    /** 固定只读 Git 操作失败。 */
    GIT_READ_FAILED,

    /** 当前平台找不到可用的精确文本搜索引擎。 */
    SEARCH_UNAVAILABLE,

    /** 精确搜索的机器输出违反约定协议。 */
    SEARCH_PROTOCOL_VIOLATION,

    /** Tool Adapter 已响应当前 Run 的取消信号。 */
    OPERATION_CANCELLED,

    /** 固定只读操作达到墙钟期限。 */
    OPERATION_TIMED_OUT,

    /** Tool 或 Adapter 的有界输出超过允许预算。 */
    OUTPUT_LIMIT_EXCEEDED,

    /** Runtime 无法归类的内部错误。 */
    INTERNAL_ERROR
}
