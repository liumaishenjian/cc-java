package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 把一次 Tool 执行结果作为规范消息反馈给模型。
 *
 * <p>其中的 Call ID 必须与此前 Assistant Message 中恰好一个 Tool Call 对应。
 * Runtime 在所有同批结果追加完成后，才允许请求下一个模型回合。</p>
 *
 * @param result 已经过 Pipeline 规范化的 Tool Result
 * @since 0.1.0
 */
public record ToolResultMessage(ToolResult result) implements AgentMessage {

    /**
     * 创建反馈给模型的 Tool Result 消息。
     *
     * @param result 已经过 Pipeline 规范化的 Tool Result
     * @throws NullPointerException Tool Result 为空时
     */
    public ToolResultMessage {
        Objects.requireNonNull(result, "result 不能为空");
    }
}
