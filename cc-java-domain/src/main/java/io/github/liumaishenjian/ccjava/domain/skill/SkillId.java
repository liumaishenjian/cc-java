package io.github.liumaishenjian.ccjava.domain.skill;

import java.util.Objects;

/**
 * Skill 的规范逻辑身份。
 *
 * <p>本地身份允许 1～64 个小写 ASCII kebab-case；受信 Plugin Skill 使用固定
 * {@code plugin__id__skills__component} namespace。两者都不包含路径语义。</p>
 *
 * @param value 规范 kebab-case 值
 * @since 0.11.0
 */
public record SkillId(String value) implements Comparable<SkillId> {
    /** 本地 Skill 名最大长度。 */
    public static final int MAX_LOCAL_LENGTH = 64;
    /** {@code plugin__<id>__skills__<component>} 全局 Skill 名最大长度。 */
    public static final int MAX_GLOBAL_LENGTH = "plugin__".length() + MAX_LOCAL_LENGTH
            + "__skills__".length() + MAX_LOCAL_LENGTH;
    /** 校验 ASCII kebab-case 身份。 */
    public SkillId {
        value = Objects.requireNonNull(value, "value 不能为空");
        boolean local = value.matches("[a-z0-9]+(?:-[a-z0-9]+)*") && value.length() <= MAX_LOCAL_LENGTH;
        boolean plugin = value.matches("plugin__[a-z0-9]+(?:-[a-z0-9]+)*__skills__[a-z0-9]+(?:-[a-z0-9]+)*")
                && value.length() <= MAX_GLOBAL_LENGTH;
        if (!local && !plugin) {
            throw new IllegalArgumentException("Skill ID 必须是本地 kebab-case 或固定 Plugin namespace");
        }
    }

    @Override
    public int compareTo(SkillId other) {
        return value.compareTo(other.value);
    }
}
