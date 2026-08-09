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
    /** 校验资源逻辑名、摘要和不可信文本。 */
    public SkillResourceSnapshot {
        logicalName = Objects.requireNonNull(logicalName, "logicalName 不能为空");
        if (logicalName.isBlank() || !logicalName.equals(logicalName.trim())
                || logicalName.startsWith("/") || logicalName.startsWith("\\")
                || logicalName.contains("\\") || logicalName.contains(":")
                || java.util.Arrays.asList(logicalName.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("logicalName 必须是安全相对逻辑名");
        }
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest 不能为空");
        if (!contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest 必须是 SHA-256");
        }
        text = Objects.requireNonNull(text, "text 不能为空");
    }

    /** @return 不含资源文本的隐私安全摘要 */
    @Override
    public String toString() {
        return "SkillResourceSnapshot[logicalName=" + logicalName
                + ", contentDigest=" + contentDigest + "]";
    }
}
