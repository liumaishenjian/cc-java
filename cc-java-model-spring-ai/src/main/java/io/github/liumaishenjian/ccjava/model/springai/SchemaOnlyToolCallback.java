package io.github.liumaishenjian.ccjava.model.springai;

import java.util.Objects;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 只把项目 Tool Schema 暴露给模型、永不允许框架执行的 Sentinel Callback。
 *
 * <p>Spring AI 的 Tool Definition API 与 Tool Callback 绑定，因此 Adapter
 * 必须提供 Callback 才能把 Schema 发送给 Ollama。该实现的执行方法始终失败，
 * 从结构上证明工具执行权仍属于 {@code ToolExecutionPipeline}。</p>
 *
 * @since 0.1.0
 */
final class SchemaOnlyToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    /**
     * 从项目 Tool 定义创建 Spring AI Schema。
     *
     * @param source 项目的框架无关 Tool 定义
     */
    SchemaOnlyToolCallback(
            io.github.liumaishenjian.ccjava.domain.ToolDefinition source) {
        Objects.requireNonNull(source, "source 不能为空");
        this.definition = ToolDefinition.builder()
                .name(source.name())
                .description(source.description())
                .inputSchema(source.inputSchemaJson())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /**
     * 拒绝 Spring AI 在 Adapter 内执行工具。
     *
     * @param arguments Provider 产生的原始参数
     * @return 永不返回
     * @throws IllegalStateException 每次调用均抛出
     */
    @Override
    public String call(String arguments) {
        throw new IllegalStateException(
                "Spring AI Adapter 不得执行 Tool；必须交回 cc-java Runtime");
    }
}
