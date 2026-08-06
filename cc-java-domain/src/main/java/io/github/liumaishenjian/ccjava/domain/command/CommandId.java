package io.github.liumaishenjian.ccjava.domain.command;

import java.util.Objects;

/**
 * 关联一次命令请求与其唯一终态结果的受限标识。
 *
 * @param value Surface 生成的非空、无控制字符标识
 * @since 0.8.0
 */
public record CommandId(String value) {
    /**
     * 创建有界命令标识。
     *
     * @param value Surface 生成的关联值
     */
    public CommandId {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("commandId 非法");
        }
    }
}
