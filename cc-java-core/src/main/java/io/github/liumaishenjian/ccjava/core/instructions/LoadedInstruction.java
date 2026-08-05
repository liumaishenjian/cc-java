package io.github.liumaishenjian.ccjava.core.instructions;

import java.util.Objects;

/**
 * Loader 私有返回值，保留 identity 与完整 digest 以供 Core 去重。
 *
 * <p>该类型绝不进入 Domain 诊断、事件、Session 或外部 Surface。</p>
 *
 * @param canonicalIdentity Adapter 验证的稳定 canonical identity
 * @param fullDigest 小写十六进制 SHA-256 内容摘要
 * @param text 已验证 UTF-8 正文
 * @since 0.8.0
 */
public record LoadedInstruction(String canonicalIdentity, String fullDigest, String text) {

    /** 校验内部身份、摘要和正文。 */
    public LoadedInstruction {
        canonicalIdentity = Objects.requireNonNull(canonicalIdentity, "canonicalIdentity 不能为空");
        fullDigest = Objects.requireNonNull(fullDigest, "fullDigest 不能为空");
        text = Objects.requireNonNull(text, "text 不能为空");
        if (canonicalIdentity.isBlank() || !fullDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("LoadedInstruction identity 或 digest 非法");
        }
    }

    /** 不在普通日志中回显正文、完整摘要或 adapter identity。 */
    @Override
    public String toString() {
        return "LoadedInstruction[redacted]";
    }
}
