package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以稳定注册顺序保存全部可见 Tool 的不可变 Registry。
 *
 * <p>Tool 名称是全局协议键，重复名称在装配阶段立即失败。内置、MCP、
 * Plugin 和未来 Sub-Agent Tool 都必须注册到该 Registry，并进入同一
 * {@link ToolExecutionPipeline}。</p>
 *
 * @since 0.1.0
 */
public final class ToolRegistry {

    private final Map<String, AgentTool> toolsByName;
    private final List<ToolDefinition> definitions;

    /**
     * 从 Tool 集合创建 Registry。
     *
     * @param tools 按模型展示顺序排列的 Tool
     * @throws IllegalArgumentException 出现重复名称时抛出
     */
    public ToolRegistry(Collection<? extends AgentTool> tools) {
        Objects.requireNonNull(tools, "tools 不能为空");
        LinkedHashMap<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            Objects.requireNonNull(tool, "tool 不能为空");
            ToolDefinition definition = Objects.requireNonNull(
                    tool.definition(),
                    "tool.definition() 不能为空");
            AgentTool previous = registered.putIfAbsent(definition.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("重复 Tool 名称: " + definition.name());
            }
        }
        toolsByName = Map.copyOf(registered);
        definitions = registered.values().stream()
                .map(AgentTool::definition)
                .toList();
    }

    /**
     * 创建空 Registry。
     *
     * @return 不包含 Tool 的 Registry
     */
    public static ToolRegistry empty() {
        return new ToolRegistry(List.of());
    }

    /**
     * 查找指定名称的 Tool。
     *
     * @param name Tool 名称
     * @return 未注册时为空
     */
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(toolsByName.get(Objects.requireNonNull(name, "name 不能为空")));
    }

    /**
     * 返回按注册顺序排列的 Definition。
     *
     * @return 不可变 Definition 列表
     */
    public List<ToolDefinition> definitions() {
        return definitions;
    }
}
