package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * Settings v1 中允许出现在诊断与 provenance 的固定字段路径。
 *
 * @since 0.8.0
 */
public enum SettingPath {
    /** schema 版本字段。 */ SCHEMA_VERSION("schemaVersion"),
    /** 模型名字段。 */ MODEL_NAME("model.name"),
    /** 权限模式字段。 */ PERMISSION_MODE("permission.mode"),
    /** 权限规则字段。 */ PERMISSION_RULES("permission.rules"),
    /** Tool 可见列表字段。 */ TOOLS_ENABLED("tools.enabled"),
    /** Tool 配置字段。 */ TOOLS_CONFIG("tools.config"),
    /** 上下文压缩锚点字段。 */ CONTEXT_COMPACT_INSTRUCTIONS("context.compactInstructions"),
    /** 诊断详细程度字段。 */ DIAGNOSTICS_VERBOSITY("diagnostics.verbosity");

    private final String value;

    /**
     * 创建不含来源信息的固定字段路径。
     *
     * @param value schema 定义的字面路径
     */
    SettingPath(String value) { this.value = value; }

    /**
     * 返回稳定的、无来源信息的字段路径。
     *
     * @return schema 定义的字面路径
     */
    public String value() { return value; }
}
