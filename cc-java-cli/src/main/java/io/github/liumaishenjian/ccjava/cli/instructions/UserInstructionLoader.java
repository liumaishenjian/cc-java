package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoadResult;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoader;
import io.github.liumaishenjian.ccjava.core.instructions.LoadedInstruction;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 将独立 user-root 安全读取结果转换为 Core 指令加载契约。
 *
 * <p>只接受固定 USER 候选，绝不从候选标识推导或公开用户目录路径。</p>
 *
 * @since 0.8.0
 */
public final class UserInstructionLoader implements InstructionLoader {
    private final UserInstructionRootGuard guard;

    /**
     * 建立固定 user-root 的加载 Adapter。
     *
     * @param guard 已由 Composition Root 建立的独立安全守卫
     */
    public UserInstructionLoader(UserInstructionRootGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
    }

    @Override
    public InstructionLoadResult load(InstructionCandidate candidate, CancellationToken cancellationToken) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.CANCELLED);
        }
        if (candidate.sourceKind() != InstructionSourceKind.USER
                || !candidate.safeSourceId().equals("user-instructions")) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        }
        var result = guard.load();
        if (result.text().isEmpty()) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        }
        String text = result.text().orElseThrow();
        return InstructionLoadResult.success(new LoadedInstruction("user-instructions", sha256(text), text));
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }
}
