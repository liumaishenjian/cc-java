package io.github.liumaishenjian.ccjava.domain.instructions;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 已解析指令的内部投影。
 *
 * <p>正文仅可沿 Core 到模型请求投影的内部路径传递；诊断、事件、Session 和外部 Surface
 * 不得访问或回显 {@code boundedText}。本类型在构造时保证单文件 ADR 上限，避免未验证内容
 * 被作为已解析指令发布。</p>
 *
 * @param provenance 隐私安全来源元数据
 * @param boundedText 已验证、严格 UTF-8 且受单文件上限约束的正文
 * @since 0.8.0
 */
public record ResolvedInstruction(InstructionProvenance provenance, String boundedText) {

    /** 单个已解析正文允许的最大 UTF-8 字节数。 */
    public static final int MAX_UTF8_BYTES = 32 * 1024;
    /** 单个已解析正文允许的最大行数。 */
    public static final int MAX_LINES = 1_000;

    /** 校验来源元数据和已验证正文边界。 */
    public ResolvedInstruction {
        provenance = Objects.requireNonNull(provenance, "provenance 不能为空");
        boundedText = Objects.requireNonNull(boundedText, "boundedText 不能为空");
        if (boundedText.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES
                || lineCount(boundedText) > MAX_LINES) {
            throw new IllegalArgumentException("boundedText 超出指令上限");
        }
    }

    private static int lineCount(String text) {
        return text.isEmpty() ? 0 : (int) text.chars().filter(value -> value == '\n').count() + 1;
    }

    /** 防止正文经普通日志或异常字符串泄露。 */
    @Override
    public String toString() {
        return "ResolvedInstruction[provenance=" + provenance + ", boundedText=redacted]";
    }
}
