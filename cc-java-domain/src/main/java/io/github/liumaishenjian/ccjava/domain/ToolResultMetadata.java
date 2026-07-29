package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * 描述 Tool Result 的有界输出、语义裁剪与继续读取信息。
 *
 * <p>该值对象只携带框架无关统计，不包含文件正文、绝对路径或异常。Tool 可以先报告
 * 语义裁剪，Pipeline 随后必须根据最终可见正文重新绑定 {@code returnedCharacters}，
 * 并在必要时把截断原因升级为 {@link ToolResultTruncationReason#PIPELINE_CHARACTER_LIMIT}。</p>
 *
 * @param truncated              最终结果是否被裁剪
 * @param truncationReason       裁剪原因；未裁剪时必须为 {@code NONE}
 * @param returnedCharacters     最终反馈给模型的 Unicode 字符数
 * @param knownOriginalCharacters 已知的裁剪前字符数；无法低成本得知时为空
 * @param returnedItems          返回的语义条目数
 * @param filteredItems          因安全策略过滤、跳过或脱敏的条目数
 * @param continuation           可供模型发起后续调用的结构化游标
 * @since 0.3.0
 */
public record ToolResultMetadata(
        boolean truncated,
        ToolResultTruncationReason truncationReason,
        int returnedCharacters,
        OptionalLong knownOriginalCharacters,
        long returnedItems,
        long filteredItems,
        JsonObject continuation) {

    /**
     * 校验统计和截断不变量后创建 metadata。
     *
     * @throws NullPointerException 必填引用为空时
     * @throws IllegalArgumentException 计数为负数、截断状态冲突或已知原始字符数小于返回数时
     */
    public ToolResultMetadata {
        truncationReason = Objects.requireNonNull(truncationReason, "truncationReason 不能为空");
        knownOriginalCharacters = Objects.requireNonNull(
                knownOriginalCharacters,
                "knownOriginalCharacters 不能为空");
        continuation = Objects.requireNonNull(continuation, "continuation 不能为空");
        if (returnedCharacters < 0 || returnedItems < 0 || filteredItems < 0) {
            throw new IllegalArgumentException("Tool Result 统计不能为负数");
        }
        if (truncated == (truncationReason == ToolResultTruncationReason.NONE)) {
            throw new IllegalArgumentException("truncated 与 truncationReason 不一致");
        }
        if (knownOriginalCharacters.isPresent()
                && knownOriginalCharacters.getAsLong() < returnedCharacters) {
            throw new IllegalArgumentException("knownOriginalCharacters 不能小于 returnedCharacters");
        }
    }

    /**
     * 为未经语义裁剪的正文创建基础 metadata。
     *
     * @param content 最终正文
     * @return 未截断 metadata
     */
    public static ToolResultMetadata complete(String content) {
        int characters = Objects.requireNonNull(content, "content 不能为空")
                .codePointCount(0, content.length());
        return new ToolResultMetadata(
                false,
                ToolResultTruncationReason.NONE,
                characters,
                OptionalLong.of(characters),
                0,
                0,
                JsonObject.empty());
    }

    /**
     * 以 Pipeline 最终正文重新绑定字符数和截断状态。
     *
     * @param content 最终可见正文
     * @param pipelineTruncated Pipeline 是否执行了防御性裁剪
     * @param originalCharacters Pipeline 已观察到的裁剪前字符数
     * @return 规范化后的 metadata
     */
    public ToolResultMetadata normalize(
            String content,
            boolean pipelineTruncated,
            long originalCharacters) {
        int characters = Objects.requireNonNull(content, "content 不能为空")
                .codePointCount(0, content.length());
        OptionalLong original = pipelineTruncated
                ? OptionalLong.of(knownOriginalCharacters.isPresent()
                        ? Math.max(knownOriginalCharacters.getAsLong(), originalCharacters)
                        : originalCharacters)
                : knownOriginalCharacters;
        return new ToolResultMetadata(
                truncated || pipelineTruncated,
                pipelineTruncated
                        ? ToolResultTruncationReason.PIPELINE_CHARACTER_LIMIT
                        : truncationReason,
                characters,
                original,
                returnedItems,
                filteredItems,
                continuation);
    }
}
