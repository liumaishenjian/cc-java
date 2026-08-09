package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Skill root 内按需读取的不可变资源文本。
 *
 * @param logicalName 相对逻辑名，不是物理路径
 * @param contentDigest SHA-256
 * @param text 不可信 UTF-8 文本
 * @since 0.11.0
 */
public record SkillResourceSnapshot(String logicalName, String contentDigest, String text) {
    public SkillResourceSnapshot {
        logicalName = Objects.requireNonNull(logicalName, "logicalName 不能为空");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        text = Objects.requireNonNull(text, "text 不能为空");
    }
}
