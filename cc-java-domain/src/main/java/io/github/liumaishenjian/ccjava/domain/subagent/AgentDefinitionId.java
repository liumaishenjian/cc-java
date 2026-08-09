package io.github.liumaishenjian.ccjava.domain.subagent;

/**
 * 标识一次 Session 快照中的 Agent definition。
 *
 * @param value 有界 ASCII 协议标识
 * @since 0.12.0
 */
public record AgentDefinitionId(String value) {
    public AgentDefinitionId {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Agent definition ID 格式无效");
        }
    }
}
