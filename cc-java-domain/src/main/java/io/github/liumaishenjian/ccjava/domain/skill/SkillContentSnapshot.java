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
    public SkillContentSnapshot {
        skillId = Objects.requireNonNull(skillId, "skillId 不能为空");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        markdown = Objects.requireNonNull(markdown, "markdown 不能为空");
    }
}
