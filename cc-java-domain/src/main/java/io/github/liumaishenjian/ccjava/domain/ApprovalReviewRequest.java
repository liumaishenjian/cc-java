package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;

/**
 * 自动审批审查端口可见的隐私安全、有界请求。
 *
 * <p>该值只包含关联 ID、可信 Tool 元数据、专用安全摘要与近期规范上下文的脱敏投影，
 * 不包含原始 Tool 参数、selector value、Prompt、文件正文、完整源码、绝对路径或 Secret。
 * 所有集合在构造时防御性复制，并同时执行单项与总 code point 硬限界。</p>
 *
 * @param sessionId 当前 Session
 * @param runId 当前 Run
 * @param callId 当前 Tool Call ID
 * @param toolName 可信 Tool 名称
 * @param effect 可信 Tool Effect
 * @param source 可信 Tool Source
 * @param scoped 是否存在具体但未公开的 selector scope
 * @param summary 最多 512 code point 的安全摘要
 * @param recentContext 最多 8 条的规范近期上下文脱敏投影
 * @since 0.15.0
 */
public record ApprovalReviewRequest(
        SessionId sessionId,
        RunId runId,
        String callId,
        String toolName,
        ToolEffect effect,
        ToolSource source,
        boolean scoped,
        String summary,
        List<ApprovalReviewContextItem> recentContext) {

    public static final int MAX_CONTEXT_ITEMS = 8;
    public static final int MAX_CONTEXT_TOTAL_CODE_POINTS = 1_024;
    private static final int MAX_IDENTIFIER_CODE_POINTS = 128;
    private static final int MAX_TOOL_NAME_CODE_POINTS = 128;
    private static final int MAX_SUMMARY_CODE_POINTS = 512;

    /** 校验所有字段、复制集合并实施硬上限。 */
    public ApprovalReviewRequest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        callId = bounded(callId, "callId", MAX_IDENTIFIER_CODE_POINTS, false);
        toolName = bounded(toolName, "toolName", MAX_TOOL_NAME_CODE_POINTS, false);
        effect = Objects.requireNonNull(effect, "effect 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        summary = bounded(summary, "summary", MAX_SUMMARY_CODE_POINTS, true);
        recentContext = List.copyOf(Objects.requireNonNull(recentContext, "recentContext 不能为空"));
        if (recentContext.size() > MAX_CONTEXT_ITEMS) {
            throw new IllegalArgumentException("recentContext 条目过多");
        }
        int total = 0;
        for (ApprovalReviewContextItem item : recentContext) {
            total = Math.addExact(total, Objects.requireNonNull(item, "context item 不能为空")
                    .summary().codePointCount(0, item.summary().length()));
        }
        if (total > MAX_CONTEXT_TOTAL_CODE_POINTS) {
            throw new IllegalArgumentException("recentContext 总长度超限");
        }
    }

    /**
     * 保持 Batch A 调用兼容；无近期上下文时使用空投影。
     *
     * @param sessionId 当前 Session
     * @param runId 当前 Run
     * @param callId 当前 Tool Call ID
     * @param toolName 可信 Tool 名称
     * @param effect 可信 Tool Effect
     * @param source 可信 Tool Source
     * @param scoped 是否存在未公开的具体 selector scope
     * @param summary 宿主生成的安全摘要
     */
    public ApprovalReviewRequest(SessionId sessionId, RunId runId, String callId, String toolName,
            ToolEffect effect, ToolSource source, boolean scoped, String summary) {
        this(sessionId, runId, callId, toolName, effect, source, scoped, summary, List.of());
    }

    private static String bounded(String value, String name, int maximum, boolean allowEmpty) {
        Objects.requireNonNull(value, name + " 不能为空");
        int codePoints = value.codePointCount(0, value.length());
        if ((!allowEmpty && codePoints == 0) || codePoints > maximum) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 不能包含控制字符");
        }
        return value;
    }
}
