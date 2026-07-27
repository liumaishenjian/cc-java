package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import java.util.Objects;
import java.util.Optional;

/**
 * Tool 实现返回给 Pipeline 的、尚未绑定 Call ID 的业务结果。
 *
 * <p>Tool 只能描述执行是否成功以及安全输出，最终
 * {@link io.github.liumaishenjian.ccjava.domain.ToolResult} 的 Call ID、
 * Tool 名称和状态由 Pipeline 根据原始调用绑定。</p>
 *
 * @param successful 执行是否成功
 * @param content    成功时的输出正文
 * @param error      失败时的结构化错误
 * @since 0.1.0
 */
public record ToolExecutionOutcome(
        boolean successful,
        String content,
        Optional<ToolError> error) {

    /**
     * 校验并创建 Tool 业务结果。
     *
     * @param successful 执行是否成功
     * @param content    成功时的输出正文
     * @param error      失败时的结构化错误
     * @throws NullPointerException     {@code content} 或 {@code error} 为空时抛出
     * @throws IllegalArgumentException 成功状态与错误是否存在不一致时抛出
     */
    public ToolExecutionOutcome {
        content = Objects.requireNonNull(content, "content 不能为空");
        error = Objects.requireNonNull(error, "error 不能为空");
        if (successful && error.isPresent()) {
            throw new IllegalArgumentException("成功 Outcome 不能携带 error");
        }
        if (!successful && error.isEmpty()) {
            throw new IllegalArgumentException("失败 Outcome 必须携带 error");
        }
    }

    /**
     * 创建成功业务结果。
     *
     * @param content Tool 输出正文
     * @return 成功 Outcome
     */
    public static ToolExecutionOutcome success(String content) {
        return new ToolExecutionOutcome(
                true,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.empty());
    }

    /**
     * 创建失败业务结果。
     *
     * @param error 结构化错误
     * @return 失败 Outcome
     */
    public static ToolExecutionOutcome failure(ToolError error) {
        return new ToolExecutionOutcome(
                false,
                "",
                Optional.of(Objects.requireNonNull(error, "error 不能为空")));
    }
}
