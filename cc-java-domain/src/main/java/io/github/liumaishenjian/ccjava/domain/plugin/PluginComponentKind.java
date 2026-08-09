package io.github.liumaishenjian.ccjava.domain.plugin;

/** Plugin manifest v1 的封闭组件类型。 @since 0.11.0 */
public enum PluginComponentKind {
    /** Skill 目录组件。 */ SKILLS("skills"),
    /** Hook 配置组件。 */ HOOKS("hooks"),
    /** 命名 MCP Server 配置。 */ MCP_SERVER("mcp-server"),
    /** 宿主预注册 Tool Provider 描述。 */ TOOL_PROVIDER("tool-provider");

    private final String namespaceSegment;
    PluginComponentKind(String namespaceSegment) { this.namespaceSegment = namespaceSegment; }
    /** @return 全局 namespace 使用的稳定 kind 片段 */
    public String namespaceSegment() { return namespaceSegment; }
}
