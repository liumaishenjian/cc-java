package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型适配器返回的一个已经完整聚合的回合。
 *
 * <p>S01 只处理聚合结果；流式文本和跨 Chunk Tool Call 聚合在 S02
 * 由 Adapter 实现，但最终仍必须归一化为该类型。</p>
 *
 * @param assistantMessage 本回合完整的 Assistant Message
 * @param finishReason     Provider 结束原因的规范化结果
 * @param usage            Provider 明确返回的完整 Usage；不可用时为空
 * @since 0.1.0
 */
public record ModelTurn(
        AssistantMessage assistantMessage,
        ModelFinishReason finishReason,
        Optional<ModelUsage> usage) {

    /**
     * 创建一个已经完整聚合的模型回合。
     *
     * @param assistantMessage 本回合完整的 Assistant 消息
     * @param finishReason Provider 结束原因的规范化结果
     * @param usage Provider 明确返回的完整 Usage
     * @throws NullPointerException Assistant 消息、结束原因或 Usage 容器为空时
     */
    public ModelTurn {
        assistantMessage = Objects.requireNonNull(
                assistantMessage,
                "assistantMessage 不能为空");
        finishReason = Objects.requireNonNull(finishReason, "finishReason 不能为空");
        usage = Objects.requireNonNull(usage, "usage 不能为空");
    }

    /**
     * 使用可由完整消息确定的结束原因创建兼容 S01 的聚合回合。
     *
     * <p>包含 Tool Call 时标记为 {@link ModelFinishReason#TOOL_CALLS}；
     * 非空纯文本标记为 {@link ModelFinishReason#STOP}；完全空响应标记为
     * {@link ModelFinishReason#UNKNOWN}。该构造器不伪造 Usage。</p>
     *
     * @param assistantMessage 本回合完整的 Assistant 消息
     */
    public ModelTurn(AssistantMessage assistantMessage) {
        this(
                assistantMessage,
                inferFinishReason(assistantMessage),
                Optional.empty());
    }

    /**
     * 创建最终文本回合。
     *
     * @param text 最终文本
     * @return 不包含 Tool Call 的回合
     */
    public static ModelTurn text(String text) {
        return new ModelTurn(
                AssistantMessage.text(text),
                ModelFinishReason.STOP,
                Optional.empty());
    }

    /**
     * 创建仅包含 Tool Call 的回合。
     *
     * @param calls 按模型声明顺序排列的调用
     * @return Tool Calling 回合
     */
    public static ModelTurn tools(List<ToolCall> calls) {
        return new ModelTurn(
                AssistantMessage.tools(calls),
                ModelFinishReason.TOOL_CALLS,
                Optional.empty());
    }

    private static ModelFinishReason inferFinishReason(AssistantMessage message) {
        Objects.requireNonNull(message, "assistantMessage 不能为空");
        if (!message.toolCalls().isEmpty()) {
            return ModelFinishReason.TOOL_CALLS;
        }
        return message.isEmpty()
                ? ModelFinishReason.UNKNOWN
                : ModelFinishReason.STOP;
    }
}
