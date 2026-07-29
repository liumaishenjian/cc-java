package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 模型适配器返回的一个已经完整聚合的回合。
 *
 * <p>S01 只处理聚合结果；流式文本和跨 Chunk Tool Call 聚合在 S02
 * 由 Adapter 实现，但最终仍必须归一化为该类型。</p>
 *
 * @param assistantMessage 本回合完整的 Assistant Message
 * @param metadata Provider 返回的规范化结束原因、Usage 与模型标识
 * @since 0.1.0
 */
public record ModelTurn(
        AssistantMessage assistantMessage,
        ModelTurnMetadata metadata) {

    /**
     * 创建一个已经完整聚合的模型回合。
     *
     * @param assistantMessage 本回合完整的 Assistant 消息
     * @param metadata Provider 元数据；不提供时应使用 {@link ModelTurnMetadata#unknown()}
     * @throws NullPointerException Assistant 消息或元数据为空时
     */
    public ModelTurn {
        assistantMessage = Objects.requireNonNull(
                assistantMessage,
                "assistantMessage 不能为空");
        metadata = Objects.requireNonNull(metadata, "metadata 不能为空");
    }

    /**
     * 创建没有 Provider 元数据的模型回合，供 Fake 和 S01 兼容路径使用。
     *
     * @param assistantMessage 已聚合 Assistant Message
     */
    public ModelTurn(AssistantMessage assistantMessage) {
        this(assistantMessage, ModelTurnMetadata.unknown());
    }

    /**
     * 创建最终文本回合。
     *
     * @param text 最终文本
     * @return 不包含 Tool Call 的回合
     */
    public static ModelTurn text(String text) {
        return new ModelTurn(AssistantMessage.text(text));
    }

    /**
     * 创建仅包含 Tool Call 的回合。
     *
     * @param calls 按模型声明顺序排列的调用
     * @return Tool Calling 回合
     */
    public static ModelTurn tools(List<ToolCall> calls) {
        return new ModelTurn(AssistantMessage.tools(calls));
    }
}
