package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnostic;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionProvenance;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionRevision;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstruction;
import io.github.liumaishenjian.ccjava.domain.instructions.ResolvedInstructions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 对 Adapter 已验证候选执行稳定排序、identity+digest 去重与原子投影构造的发现服务。
 *
 * <p>本服务不认识路径、文件系统或用户目录；它假定 Adapter 已完成真实路径、普通文件、
 * UTF-8 和 TOCTOU 验证。取消时抛出 {@link InstructionDiscoveryCancelledException}，调用者因而
 * 不会获得可发布的部分结果。</p>
 *
 * @since 0.8.0
 */
public final class DeterministicInstructionDiscovery implements InstructionDiscovery {

    /** 单轮最多加载的候选文件数。 */
    public static final int MAX_FILES = 16;
    /** Adapter 可提交的最大目录层级。 */
    public static final int MAX_DIRECTORY_DEPTH = 8;
    /** 全部成功正文的最大 UTF-8 字节数。 */
    public static final int MAX_TOTAL_UTF8_BYTES = 128 * 1024;

    private final InstructionLoader loader;

    /**
     * 创建发现服务。
     *
     * @param loader 负责受限文件读取和身份验证的 Adapter Port
     */
    public DeterministicInstructionDiscovery(InstructionLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader 不能为空");
    }

    @Override
    public ResolvedInstructions discover(
            InstructionDiscoveryRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        checkCancelled(cancellationToken);
        List<InstructionCandidate> candidates = request.candidates().stream()
                .sorted(Comparator.comparingInt(InstructionCandidate::precedence)
                        .thenComparing(InstructionCandidate::safeSourceId))
                .toList();
        List<ResolvedInstruction> resolved = new ArrayList<>();
        List<LoadedInstruction> accepted = new ArrayList<>();
        List<InstructionDiagnostic> diagnostics = new ArrayList<>();
        Set<IdentityDigestKey> identityDigestPairs = new HashSet<>();
        int totalBytes = 0;
        int attemptedFiles = 0;
        boolean totalLimitReached = false;
        for (InstructionCandidate candidate : candidates) {
            if (attemptedFiles >= MAX_FILES) {
                diagnostics.add(diagnostic(candidate, InstructionDiagnosticCode.COUNT_LIMIT, 0));
                continue;
            }
            if (totalLimitReached) {
                diagnostics.add(diagnostic(candidate, InstructionDiagnosticCode.LIMIT_EXCEEDED, 0));
                continue;
            }
            checkCancelled(cancellationToken);
            attemptedFiles++;
            InstructionLoadResult result = Objects.requireNonNull(
                    loader.load(candidate, cancellationToken), "loader 返回不能为空");
            checkCancelled(cancellationToken);
            if (result.failureCode().isPresent()) {
                diagnostics.add(diagnostic(candidate, result.failureCode().orElseThrow(), 0));
                continue;
            }
            LoadedInstruction loaded = result.loaded().orElseThrow();
            int byteLength = loaded.text().getBytes(StandardCharsets.UTF_8).length;
            if (byteLength > 32 * 1024 || lineCount(loaded.text()) > 1_000) {
                diagnostics.add(diagnostic(candidate, InstructionDiagnosticCode.LIMIT_EXCEEDED, bucket(byteLength)));
                continue;
            }
            if (totalBytes + byteLength > MAX_TOTAL_UTF8_BYTES) {
                diagnostics.add(diagnostic(candidate, InstructionDiagnosticCode.LIMIT_EXCEEDED, bucket(byteLength)));
                totalLimitReached = true;
                continue;
            }
            IdentityDigestKey identityDigestPair =
                    new IdentityDigestKey(loaded.canonicalIdentity(), loaded.fullDigest());
            if (!identityDigestPairs.add(identityDigestPair)) {
                diagnostics.add(diagnostic(candidate, InstructionDiagnosticCode.DUPLICATE_SUPPRESSED, 0));
                continue;
            }
            totalBytes += byteLength;
            accepted.add(loaded);
            resolved.add(new ResolvedInstruction(new InstructionProvenance(
                    candidate.sourceKind(), candidate.scopeKind(), candidate.safeSourceId(),
                    loaded.fullDigest().substring(0, 12), candidate.precedence(), candidate.activation()),
                    loaded.text()));
        }
        checkCancelled(cancellationToken);
        return new ResolvedInstructions(resolved, diagnostics, revision(resolved, accepted, diagnostics));
    }

    private static InstructionDiagnostic diagnostic(
            InstructionCandidate candidate, InstructionDiagnosticCode code, int lengthBucket) {
        return new InstructionDiagnostic(candidate.sourceKind(), candidate.safeSourceId(), code,
                lengthBucket, InstructionDiagnosticSeverity.WARNING);
    }

    private static int bucket(int length) {
        if (length == 0) {
            return 0;
        }
        return Integer.highestOneBit(length);
    }

    private static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static void checkCancelled(CancellationToken token) {
        if (token.isCancellationRequested()) {
            throw new InstructionDiscoveryCancelledException();
        }
    }

    /**
     * 用类型化二元组表达去重键，避免字符串拼接产生分隔符碰撞或非文本源码字节。
     */
    private record IdentityDigestKey(String canonicalIdentity, String fullDigest) {
    }

    private static InstructionRevision revision(
            List<ResolvedInstruction> items,
            List<LoadedInstruction> accepted,
            List<InstructionDiagnostic> diagnostics) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < items.size(); index++) {
                InstructionProvenance provenance = items.get(index).provenance();
                LoadedInstruction loaded = accepted.get(index);
                update(digest, provenance.sourceKind().name());
                update(digest, provenance.scopeKind().name());
                update(digest, provenance.safeSourceId());
                update(digest, loaded.fullDigest());
                update(digest, Integer.toString(provenance.precedence()));
                update(digest, provenance.activation().name());
            }
            for (InstructionDiagnostic diagnostic : diagnostics) {
                update(digest, diagnostic.sourceKind().name());
                update(digest, diagnostic.safeSourceId());
                update(digest, diagnostic.code().name());
                update(digest, Integer.toString(diagnostic.lengthBucket()));
            }
            return new InstructionRevision(java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
