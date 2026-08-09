package io.github.liumaishenjian.ccjava.domain;

import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import java.util.Objects;

/**
 * 仅存在于当前 Run 模型请求中的不可信 Skill 正文投影。
 *
 * <p>该消息不是 System 指令，也没有进入 Canonical Transcript 或 Session JSONL 的资格。
 * Adapter 应把身份、调用来源和正文作为明确的不可信上下文映射；正文中的 Tool、权限或
 * Hook 声明不能改变确定性的 Runtime 边界。</p>
 *
 * @param skillId Skill 规范身份
 * @param snapshotId catalog 内容身份
 * @param contentDigest 正文内容摘要
 * @param invocationKind 激活入口
 * @param arguments 有界的不可信调用参数
 * @param markdown 有界的不可信 Markdown 正文及资源投影
 * @since 0.11.0
 */
public record SkillContextMessage(
        SkillId skillId,
        String snapshotId,
        String contentDigest,
        SkillInvocationKind invocationKind,
        String arguments,
        String markdown) implements AgentMessage {

    /** 验证 Projection-only 消息的身份与内容边界。 */
    public SkillContextMessage {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        snapshotId = requireDigest(snapshotId, "snapshotId");
        contentDigest = requireDigest(contentDigest, "contentDigest");
        invocationKind = Objects.requireNonNull(invocationKind, "invocationKind 不能为空");
        arguments = Objects.requireNonNull(arguments, "arguments 不能为空");
        markdown = Objects.requireNonNull(markdown, "markdown 不能为空");
        if (arguments.codePointCount(0, arguments.length()) > 8_192
                || markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_179_648) {
            throw new IllegalArgumentException("Skill Context 超过固定边界");
        }
    }

    private static String requireDigest(String value, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " 必须是 SHA-256");
        return value;
    }

    /** @return 不泄露参数或正文的隐私安全摘要 */
    @Override
    public String toString() {
        return "SkillContextMessage[skillId=" + skillId.value() + ", snapshotId=" + snapshotId
                + ", contentDigest=" + contentDigest + ", invocationKind=" + invocationKind
                + ", argumentCodePoints=" + arguments.codePointCount(0, arguments.length())
                + ", markdownCodePoints=" + markdown.codePointCount(0, markdown.length()) + "]";
    }
}
