package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个候选加载的成功值或不含底层异常的失败分类。
 *
 * @param loaded 成功时由 Adapter 验证的内容；失败时为空
 * @param failureCode 失败时的封闭诊断分类；成功时为空
 * @since 0.8.0
 */
public record InstructionLoadResult(
        Optional<LoadedInstruction> loaded,
        Optional<InstructionDiagnosticCode> failureCode) {

    /** 保证加载结果恰好表达成功或失败之一。 */
    public InstructionLoadResult {
        loaded = Objects.requireNonNull(loaded, "loaded 不能为空");
        failureCode = Objects.requireNonNull(failureCode, "failureCode 不能为空");
        if (loaded.isPresent() == failureCode.isPresent()) {
            throw new IllegalArgumentException("加载结果必须恰好包含成功值或失败 code");
        }
    }

    /**
     * 创建成功结果。
     *
     * @param loaded 已验证内容
     * @return 成功加载结果
     */
    public static InstructionLoadResult success(LoadedInstruction loaded) {
        return new InstructionLoadResult(Optional.of(loaded), Optional.empty());
    }

    /**
     * 创建失败结果。
     *
     * @param code 不泄露底层信息的失败分类
     * @return 失败加载结果
     */
    public static InstructionLoadResult failure(InstructionDiagnosticCode code) {
        return new InstructionLoadResult(Optional.empty(), Optional.of(code));
    }

    /** 不在普通日志中回显内部 LoadedInstruction。 */
    @Override
    public String toString() {
        return failureCode.map(code -> "InstructionLoadResult[failure=" + code + "]")
                .orElse("InstructionLoadResult[loaded]");
    }
}
