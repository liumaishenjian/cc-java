package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Tool Call 经 Pipeline 规范化后的结果。
 *
 * <p>{@code callId} 和 {@code toolName} 由 Pipeline 根据原始调用绑定，Tool
 * 实现不能自行伪造。成功结果不得携带错误，失败或拒绝结果必须携带错误。</p>
 *
 * @param callId   对应原始 Tool Call 的 ID
 * @param toolName 对应原始 Tool Call 的名称
 * @param status   规范化状态
 * @param content  可反馈给模型的文本；没有正文时为空字符串
 * @param error    失败或拒绝时的结构化错误
 * @param metadata 输出边界、裁剪和 continuation 元数据
 * @since 0.1.0
 */
public record ToolResult(
        String callId,
        String toolName,
        ToolResultStatus status,
        String content,
        Optional<ToolError> error,
        ToolResultMetadata metadata) {

    /**
     * 校验调用关联和状态不变量后创建 Tool Result。
     *
     * @param callId 对应原始 Tool Call 的 ID
     * @param toolName 对应原始 Tool Call 的名称
     * @param status 规范化结果状态
     * @param content 可反馈给模型的文本
     * @param error 失败或拒绝时的结构化错误
     * @param metadata 输出边界、裁剪和 continuation 元数据
     * @throws NullPointerException 必填引用或 Optional 容器为空时
     * @throws IllegalArgumentException 标识为空白，或状态与错误信息不一致时
     */
    public ToolResult {
        callId = requireText(callId, "callId");
        toolName = requireText(toolName, "toolName");
        status = Objects.requireNonNull(status, "status 不能为空");
        content = Objects.requireNonNull(content, "content 不能为空");
        error = Objects.requireNonNull(error, "error 不能为空");
        metadata = Objects.requireNonNull(metadata, "metadata 不能为空");
        if (metadata.returnedCharacters() != content.codePointCount(0, content.length())) {
            throw new IllegalArgumentException("metadata 返回字符数必须与 content 一致");
        }
        if (status == ToolResultStatus.SUCCESS && error.isPresent()) {
            throw new IllegalArgumentException("成功结果不能携带 error");
        }
        if (status != ToolResultStatus.SUCCESS && error.isEmpty()) {
            throw new IllegalArgumentException("失败或拒绝结果必须携带 error");
        }
    }

    /**
     * 创建成功结果。
     *
     * @param callId   原始 Call ID
     * @param toolName Tool 名称
     * @param content  输出正文
     * @return 成功结果
     */
    public static ToolResult success(String callId, String toolName, String content) {
        return new ToolResult(
                callId,
                toolName,
                ToolResultStatus.SUCCESS,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.empty(),
                ToolResultMetadata.complete(content));
    }

    /**
     * 创建携带语义裁剪 metadata 的成功结果。
     *
     * @param callId 原始 Call ID
     * @param toolName Tool 名称
     * @param content 输出正文
     * @param metadata Tool 已产生的语义裁剪信息
     * @return 成功结果
     */
    public static ToolResult success(
            String callId,
            String toolName,
            String content,
            ToolResultMetadata metadata) {
        return new ToolResult(
                callId,
                toolName,
                ToolResultStatus.SUCCESS,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.empty(),
                Objects.requireNonNull(metadata, "metadata 不能为空"));
    }

    /**
     * 创建可恢复失败结果。
     *
     * @param callId   原始 Call ID
     * @param toolName Tool 名称
     * @param error    结构化错误
     * @return 失败结果
     */
    public static ToolResult failure(String callId, String toolName, ToolError error) {
        return new ToolResult(
                callId,
                toolName,
                ToolResultStatus.FAILURE,
                "",
                Optional.of(Objects.requireNonNull(error, "error 不能为空")),
                ToolResultMetadata.complete(""));
    }

    /**
     * 创建保留有界失败证据的结果。
     *
     * @param callId 原始 Call ID
     * @param toolName Tool 名称
     * @param content 已裁剪、可反馈给模型的失败证据
     * @param error 类型化失败
     * @param metadata 证据输出边界
     * @return 失败结果
     */
    public static ToolResult failure(String callId, String toolName, String content,
            ToolError error, ToolResultMetadata metadata) {
        return new ToolResult(callId, toolName, ToolResultStatus.FAILURE,
                Objects.requireNonNull(content, "content 不能为空"),
                Optional.of(Objects.requireNonNull(error, "error 不能为空")),
                Objects.requireNonNull(metadata, "metadata 不能为空"));
    }

    /**
     * 创建权限拒绝结果。
     *
     * @param callId   原始 Call ID
     * @param toolName Tool 名称
     * @param message  拒绝说明
     * @return 拒绝结果
     */
    public static ToolResult denied(String callId, String toolName, String message) {
        return new ToolResult(
                callId,
                toolName,
                ToolResultStatus.DENIED,
                "",
                Optional.of(ToolError.of(ToolErrorCode.PERMISSION_DENIED, message)),
                ToolResultMetadata.complete(""));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
