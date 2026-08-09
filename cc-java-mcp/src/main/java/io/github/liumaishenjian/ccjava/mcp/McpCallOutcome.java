package io.github.liumaishenjian.ccjava.mcp;

import java.util.Objects;

/**
 * MCP 调用经脱敏和有界化后的 SDK 无关结果。
 *
 * @param error Server 是否把本次调用标记为 Tool 错误
 * @param content 供统一 Pipeline 继续裁剪的文本投影
 */
public record McpCallOutcome(boolean error, String content) {
    /** 校验文本投影不为 {@code null}。 */
    public McpCallOutcome {
        content = Objects.requireNonNull(content, "content 不能为空");
    }
}
