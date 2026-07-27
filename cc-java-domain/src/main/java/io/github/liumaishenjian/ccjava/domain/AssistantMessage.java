package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 表示一个已经聚合完成的模型回合输出。
 *
 * <p>文本和 Tool Call 可以同时存在。一个模型回合无论包含多少 Tool Call，
 * 该消息都只能向规范历史追加一次，并完整持有该回合的全部调用。空文本且没有
 * Tool Call 的实例用于表达 Provider 的无效响应，由 Agent Runtime 决定停止。</p>
 *
 * @param text      模型文本，可以为空字符串
 * @param toolCalls 本回合按模型声明顺序产生的全部 Tool Call
 * @since 0.1.0
 */
public record AssistantMessage(String text, List<ToolCall> toolCalls) implements AgentMessage {

    /**
     * 防御性复制 Tool Call 列表后创建 Assistant 消息。
     *
     * @param text 模型文本，可以为空字符串
     * @param toolCalls 本回合按声明顺序产生的 Tool Call
     * @throws NullPointerException 文本、列表或列表元素为空时
     */
    public AssistantMessage {
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(toolCalls, "toolCalls 不能为空");
        toolCalls = List.copyOf(toolCalls);
    }

    /**
     * 判断该响应是否同时缺少文本和工具调用。
     *
     * @return 无任何有效内容时返回 {@code true}
     */
    public boolean isEmpty() {
        return text.isBlank() && toolCalls.isEmpty();
    }

    /**
     * 创建仅包含最终文本的 Assistant Message。
     *
     * @param text 模型最终文本
     * @return 不包含 Tool Call 的消息
     */
    public static AssistantMessage text(String text) {
        return new AssistantMessage(text, List.of());
    }

    /**
     * 创建包含 Tool Call 的 Assistant Message。
     *
     * @param toolCalls 本回合的工具调用
     * @return 文本为空、包含给定调用的消息
     */
    public static AssistantMessage tools(List<ToolCall> toolCalls) {
        return new AssistantMessage("", toolCalls);
    }
}
