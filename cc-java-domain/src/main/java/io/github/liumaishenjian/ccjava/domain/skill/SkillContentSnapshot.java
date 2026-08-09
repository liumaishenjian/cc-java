package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * 调用时经 digest/identity 重检得到的不可变 Markdown 正文。
 *
 * @param skillId Skill 身份
 * @param snapshotId Catalog 快照
 * @param contentDigest 完整文件 digest
 * @param markdown 不可信、有界正文
 * @since 0.11.0
 */
public record SkillContentSnapshot(SkillId skillId, String snapshotId, String contentDigest, String markdown) {
    /** 校验调用时正文快照的必需身份和内容。 */
    public SkillContentSnapshot {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        snapshotId = requireDigest(snapshotId, "snapshotId");
        contentDigest = requireDigest(contentDigest, "contentDigest");
        markdown = Objects.requireNonNull(markdown, "markdown 不能为空");
    }

    private static String requireDigest(String value, String field) {
        value = Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " 必须是 SHA-256");
        }
        return value;
    }

    /** @return 不含 Markdown 正文的隐私安全摘要 */
    @Override
    public String toString() {
        return "SkillContentSnapshot[skillId=" + skillId.value()
                + ", snapshotId=" + snapshotId
                + ", contentDigest=" + contentDigest + "]";
    }
}
