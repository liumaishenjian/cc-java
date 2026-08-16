package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 自动审批可见的单条规范近期上下文投影。
 *
 * <p>该投影只允许固定角色与宿主生成的脱敏摘要，不承载原始 Prompt、文件正文、源码、
 * Tool 参数或凭证。构造时按 Unicode code point 强制单项上限。</p>
 *
 * @param role 固定规范消息角色
 * @param summary 宿主生成的脱敏摘要
 * @since 0.15.0
 */
public record ApprovalReviewContextItem(Role role, String summary) {
    /** 单项摘要上限。 */
    public static final int MAX_SUMMARY_CODE_POINTS = 256;

    /** 允许投影的固定规范角色。 */
    public enum Role {
        /** 用户消息的存在性摘要。 */
        USER,
        /** Assistant 消息的存在性摘要。 */
        ASSISTANT,
        /** 已完成 Tool Result 的存在性摘要。 */
        TOOL_RESULT
    }

    /** 校验角色、控制字符和硬上限。 */
    public ApprovalReviewContextItem {
        role = Objects.requireNonNull(role, "role 不能为空");
        summary = Objects.requireNonNull(summary, "summary 不能为空");
        int length = summary.codePointCount(0, summary.length());
        if (length == 0 || length > MAX_SUMMARY_CODE_POINTS) {
            throw new IllegalArgumentException("context summary 超出允许范围");
        }
        if (summary.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("context summary 不能包含控制字符");
        }
    }
}
