package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoadResult;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoader;
import io.github.liumaishenjian.ccjava.core.instructions.LoadedInstruction;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.tools.local.tool.Utf8TextReader;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 以既有 WorkspaceGuard 加载 Project、Directory 与 Local Instructions 的 Adapter。
 *
 * <p>候选的安全逻辑标识必须是 workspace-relative 文件名；本类型不从模型文本推导路径。
 * 每次读取均以 no-follow 普通文件、严格 UTF-8、大小/行数上限与读取前后 identity 复验
 * Fail Closed。该 Loader 尚未接入 Headless Runtime。</p>
 *
 * @since 0.8.0
 */
public final class WorkspaceInstructionLoader implements InstructionLoader {

    /** 单个 instruction 文件的最大 UTF-8 字节数。 */
    public static final int MAX_BYTES = 32 * 1024;
    /** 单个 instruction 文件的最大文本行数。 */
    public static final int MAX_LINES = 1_000;

    private static final String ROOT_AGENTS = "AGENTS.md";
    private static final String LOCAL_AGENTS = ".cc-java/AGENTS.local.md";

    private final WorkspaceGuard guard;
    private final GitIgnorePolicy gitIgnorePolicy;

    /**
     * 建立固定 Workspace 的加载器。
     *
     * @param guard 已固定真实 Workspace 的既有安全守卫
     * @param gitIgnorePolicy 固定 Local 候选的 Git ignore 证明器
     */
    public WorkspaceInstructionLoader(WorkspaceGuard guard, GitIgnorePolicy gitIgnorePolicy) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
        this.gitIgnorePolicy = Objects.requireNonNull(gitIgnorePolicy, "gitIgnorePolicy 不能为空");
    }

    @Override
    public InstructionLoadResult load(InstructionCandidate candidate, CancellationToken cancellationToken) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        if (cancellationToken.isCancellationRequested()) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.CANCELLED);
        }
        if (candidate.sourceKind() == InstructionSourceKind.USER || !isFixedCandidate(candidate)) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        }
        if (candidate.sourceKind() == InstructionSourceKind.LOCAL
                && !gitIgnorePolicy.allowsFixedLocalInstructions(cancellationToken)) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.LOCAL_INSTRUCTIONS_NOT_GITIGNORED);
        }
        try {
            InstructionPathSafety.requireNoLink(
                    guard.workspace().resolve(candidate.safeSourceId()).normalize());
            ValidatedWorkspacePath validated = guard.requireRegularFile(candidate.safeSourceId());
            return read(validated.realPath(), cancellationToken);
        } catch (WorkspaceAccessException | IOException exception) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        }
    }

    private static boolean isFixedCandidate(InstructionCandidate candidate) {
        return switch (candidate.sourceKind()) {
            case PROJECT -> candidate.safeSourceId().equals(ROOT_AGENTS);
            case DIRECTORY -> candidate.safeSourceId().endsWith("/AGENTS.md")
                    && !candidate.safeSourceId().equals(ROOT_AGENTS);
            case LOCAL -> candidate.safeSourceId().equals(LOCAL_AGENTS);
            case USER -> false;
        };
    }

    private static InstructionLoadResult read(Path path, CancellationToken cancellationToken) {
        return read(path, cancellationToken, () -> { });
    }

    static InstructionLoadResult read(Path path, CancellationToken cancellationToken, Runnable afterRead) {
        Objects.requireNonNull(afterRead, "afterRead 不能为空");
        try {
            if (cancellationToken.isCancellationRequested()) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.CANCELLED);
            }
            FileSnapshot before = snapshot(path);
            if (before.size() > MAX_BYTES) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.LIMIT_EXCEEDED);
            }
            var document = Utf8TextReader.readDocument(path, MAX_BYTES);
            byte[] bytes = document.bytes();
            String text = document.text();
            afterRead.run();
            if (lineCount(text) > MAX_LINES) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.LIMIT_EXCEEDED);
            }
            if (cancellationToken.isCancellationRequested()) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.CANCELLED);
            }
            FileSnapshot after = snapshot(path);
            if (!before.sameIdentity(after)) {
                return InstructionLoadResult.failure(InstructionDiagnosticCode.IDENTITY_CHANGED);
            }
            return InstructionLoadResult.success(new LoadedInstruction(
                    after.realPath().toString(), sha256(bytes), text));
        } catch (WorkspaceAccessException | IOException exception) {
            return InstructionLoadResult.failure(InstructionDiagnosticCode.UNREADABLE);
        }
    }

    private static FileSnapshot snapshot(Path path) throws IOException {
        BasicFileAttributes attributes = InstructionPathSafety.requireNoLink(path);
        if (!attributes.isRegularFile()) {
            throw new IOException("not a regular file");
        }
        return new FileSnapshot(path.toRealPath(), attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
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

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }

    private record FileSnapshot(
            Path realPath, Object fileKey, long size, java.nio.file.attribute.FileTime lastModifiedTime) {
        private boolean sameIdentity(FileSnapshot other) {
            return realPath.equals(other.realPath)
                    && Objects.equals(fileKey, other.fileKey)
                    && size == other.size
                    && lastModifiedTime.equals(other.lastModifiedTime);
        }
    }
}
