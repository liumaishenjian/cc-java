package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Skill 的规范逻辑身份。
 *
 * <p>身份只允许 1～64 个小写 ASCII 字母、数字和单连字符，不包含路径语义。</p>
 *
 * @param value 规范 kebab-case 值
 * @since 0.11.0
 */
public record SkillId(String value) implements Comparable<SkillId> {
    public SkillId {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || value.length() > 64) {
            throw new IllegalArgumentException("Skill ID 必须是最长 64 字符的 ASCII kebab-case");
        }
    }

    @Override
    public int compareTo(SkillId other) {
        return value.compareTo(other.value);
    }
}
