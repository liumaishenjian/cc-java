package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 提供给模型和 Runtime 的框架无关 Tool 元数据。
 *
 * <p>{@code inputSchemaJson} 保存 JSON Schema 文本，但 S01 不内置完整
 * Schema 引擎；具体 Tool 仍必须在执行前完成确定性参数校验。超时和输出上限
 * 在本阶段只是契约元数据，分别由 S04 和 S03/S07 落实执行策略。</p>
 *
 * @param name                   稳定且唯一的 Tool 名称
 * @param description            面向模型的清晰用途说明
 * @param inputSchemaJson        Tool 输入的 JSON Schema 文本
 * @param effect                 最高副作用等级
 * @param source                 Tool 注册来源
 * @param supportsCancellation   实现是否声明支持取消
 * @param defaultTimeout         建议的默认执行超时
 * @param outputMediaType        Tool 输出内容类型
 * @param maxOutputCharacters    建议的最大输出字符数
 * @since 0.1.0
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchemaJson,
        ToolEffect effect,
        ToolSource source,
        boolean supportsCancellation,
        Duration defaultTimeout,
        String outputMediaType,
        int maxOutputCharacters) {

    /**
     * 校验 Tool 元数据和资源边界后创建定义。
     *
     * @param name 稳定且唯一的 Tool 名称
     * @param description 面向模型的用途说明
     * @param inputSchemaJson Tool 输入的 JSON Schema 文本
     * @param effect 最高副作用等级
     * @param source Tool 注册来源
     * @param supportsCancellation 是否支持取消
     * @param defaultTimeout 建议的默认执行超时
     * @param outputMediaType Tool 输出内容类型
     * @param maxOutputCharacters 建议的最大输出字符数
     * @throws NullPointerException 必填引用为空时
     * @throws IllegalArgumentException 文本为空白、超时非正数或输出上限小于 1 时
     */
    public ToolDefinition {
        name = requireText(name, "name");
        description = requireText(description, "description");
        inputSchemaJson = requireText(inputSchemaJson, "inputSchemaJson");
        effect = Objects.requireNonNull(effect, "effect 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout 不能为空");
        outputMediaType = requireText(outputMediaType, "outputMediaType");
        if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout 必须大于 0");
        }
        if (maxOutputCharacters < 1) {
            throw new IllegalArgumentException("maxOutputCharacters 必须大于 0");
        }
    }

    /**
     * 创建适合 S01 Fake Tool 的只读文本定义。
     *
     * @param name            Tool 名称
     * @param description     Tool 说明
     * @param inputSchemaJson 输入 Schema
     * @return 具有保守元数据默认值的定义
     */
    public static ToolDefinition readOnlyText(
            String name,
            String description,
            String inputSchemaJson) {
        return new ToolDefinition(
                name,
                description,
                inputSchemaJson,
                ToolEffect.READ_WORKSPACE,
                ToolSource.BUILT_IN,
                false,
                Duration.ofSeconds(30),
                "text/plain",
                16_384);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
