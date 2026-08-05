package io.github.liumaishenjian.ccjava.domain.instructions;

import java.util.Objects;

/**
 * 由有序 resolved metadata 与诊断派生的稳定发现 revision。
 *
 * <p>值是完整 SHA-256 摘要，但不包含候选 identity、路径或正文。</p>
 *
 * @param value 小写十六进制 SHA-256 摘要
 * @since 0.8.0
 */
public record InstructionRevision(String value) {

    /** 校验 revision 格式。 */
    public InstructionRevision {
        value = Objects.requireNonNull(value, "value 不能为空");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Instruction revision 必须是 SHA-256 十六进制摘要");
        }
    }
}
