package io.github.liumaishenjian.ccjava.domain.subagent;

/**
 * 关联父 Tool Call 与子任务的委托身份。
 *
 * @param value 有界且不含敏感正文的关联键
 * @since 0.12.0
 */
public record DelegationId(String value) {
    /** 校验并冻结父 Tool Call 关联键。 */
    public DelegationId {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Delegation ID 格式无效");
        }
    }
}
