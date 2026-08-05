package io.github.liumaishenjian.ccjava.domain.settings;

/** Runtime Settings 投影可公开的固定拒绝原因。 @since 0.8.0 */
public enum RuntimeSettingsDiagnosticCode {
    /** 会话存在活动 Run，不能替换下一 Run 配置。 */ ACTIVE_RUN,
    /** 调用在发布候选前被取消。 */ CANCELLED,
    /** 模型名称不在当前 Provider 已支持的名称集合中。 */ UNSUPPORTED_MODEL,
    /** 模式值无法映射到既有 S05 PermissionMode。 */ INVALID_PERMISSION_MODE,
    /** 规则不能映射到已注册 builtin Tool 的既有 S05 契约。 */ INVALID_PERMISSION_RULE,
    /** Settings 请求可见的 Tool 未注册、非 builtin 或违反 shrink-only 约束。 */ INVALID_TOOL_VISIBILITY,
    /** Tool 配置目标未注册、非 builtin、已删除或不受其可信 schema 支持。 */ INVALID_TOOL_CONFIGURATION,
    /** Tool 配置尝试影响凭证、端点或执行/隔离安全边界。 */ FORBIDDEN_TOOL_CONFIGURATION,
    /** compact anchor 或 diagnostics verbosity 不能映射到受限 Runtime 输入。 */ INVALID_CONTEXT_OR_DIAGNOSTICS
}
