package io.github.liumaishenjian.ccjava.domain.governance;

import java.time.Instant;
import java.util.Objects;

/**
 * 本机管理员策略的内容身份与来源判定，不包含路径或策略正文。
 *
 * <p>{@code trusted} 只能由平台 machine-source validator 在验证固定机器 root、realpath、owner
 * 与写权限后设置；普通配置解析器不得自行置 true。LKG 标记只说明回退来源，不扩大策略权限。</p>
 *
 * @param schemaMajor 策略 schema major
 * @param digest canonical 策略字节的 SHA-256
 * @param loadedAt 本机加载时间
 * @param trusted 是否已证明机器管理员来源
 * @param lastKnownGood 是否来自 LKG 快照
 * @since 0.1.0
 */
public record ManagedPolicyProvenance(
        int schemaMajor,
        String digest,
        Instant loadedAt,
        boolean trusted,
        boolean lastKnownGood) {
    /** 校验 schema 与内容摘要，并固定加载时间。 */
    public ManagedPolicyProvenance {
        if (schemaMajor < 1) throw new IllegalArgumentException("schemaMajor 非法");
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest 非法");
        }
        loadedAt = Objects.requireNonNull(loadedAt, "loadedAt 不能为空");
    }
}
