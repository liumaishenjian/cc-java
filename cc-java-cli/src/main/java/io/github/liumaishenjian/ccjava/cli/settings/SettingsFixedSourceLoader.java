package io.github.liumaishenjian.ccjava.cli.settings;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceKind;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 加载固定 user/project/local Settings 文件的安全 Adapter。
 *
 * <p>文件名和来源标识由代码固定，调用方不能传入路径。每次读取都在 no-follow、普通文件、
 * containment、UTF-8、32 KiB 与读取前后 identity 复验下完成；失败只返回安全诊断。缺失
 * optional 文件是无候选的 no-op。该类型不合并或发布 Settings。</p>
 *
 * @since 0.8.0
 */
public final class SettingsFixedSourceLoader {
    private static final String USER_SAFE_ID = "user-settings";
    private static final String PROJECT_SHARED_SAFE_ID = "project-shared-settings";
    private static final String PROJECT_LOCAL_SAFE_ID = "project-local-settings";
    private static final String PROJECT_SHARED_PATH = ".cc-java/settings.json";
    private static final String PROJECT_LOCAL_PATH = ".cc-java/settings.local.json";
    private static final String USER_ROOT_DIRECTORY = ".cc-java";
    private static final String USER_FILE_NAME = "settings.json";

    private final Path userRoot;
    private final Path userTarget;
    private final WorkspaceGuard workspaceGuard;
    private final SettingsV1SourceParser parser;
    private final SettingsLocalGitIgnorePolicy localGitIgnorePolicy;

    /**
     * 创建固定位置加载器。
     *
     * @param userHome Composition Root 已解析一次的 user home
     * @param workspaceGuard 已固定真实 Workspace 的守卫
     * @param parser 严格 v1 解析器
     */
    public SettingsFixedSourceLoader(Path userHome, WorkspaceGuard workspaceGuard, SettingsV1SourceParser parser) {
        this(userHome, workspaceGuard, parser, new SettingsLocalGitIgnorePolicy(workspaceGuard.workspace()));
    }

    SettingsFixedSourceLoader(Path userHome, WorkspaceGuard workspaceGuard, SettingsV1SourceParser parser,
                              SettingsLocalGitIgnorePolicy localGitIgnorePolicy) {
        Path home = Objects.requireNonNull(userHome, "userHome 不能为空").toAbsolutePath().normalize();
        this.userRoot = home.resolve(USER_ROOT_DIRECTORY).normalize();
        this.userTarget = userRoot.resolve(USER_FILE_NAME).normalize();
        this.workspaceGuard = Objects.requireNonNull(workspaceGuard, "workspaceGuard 不能为空");
        this.parser = Objects.requireNonNull(parser, "parser 不能为空");
        this.localGitIgnorePolicy = Objects.requireNonNull(localGitIgnorePolicy, "localGitIgnorePolicy 不能为空");
    }

    /**
     * 加载固定 user Settings；缺失时不产生候选或诊断。
     *
     * @param cancellationToken 本次读取的取消边界
     * @return 原子来源结果，绝不包含原始文件内容
     */
    public SettingsSourceLoadResult loadUser(CancellationToken cancellationToken) {
        return loadUser(cancellationToken, () -> { });
    }

    /**
     * 加载固定 project shared Settings；缺失时不产生候选或诊断。
     *
     * @param cancellationToken 本次读取的取消边界
     * @return 原子来源结果，绝不包含原始文件内容
     */
    public SettingsSourceLoadResult loadProjectShared(CancellationToken cancellationToken) {
        return loadProject(SettingsSourceKind.PROJECT_SHARED, PROJECT_SHARED_SAFE_ID, PROJECT_SHARED_PATH, cancellationToken, () -> { });
    }

    /**
     * 加载固定且已被 Git 明确忽略的 project local Settings。
     *
     * @param cancellationToken 本次读取和 Git 证明的取消边界
     * @return 原子来源结果，Git 无法明确证明时不加载文件
     */
    public SettingsSourceLoadResult loadProjectLocal(CancellationToken cancellationToken) {
        if (Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空").isCancellationRequested()) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID, ConfigurationDiagnosticCode.CANCELLED);
        }
        try {
            if (probeOptional(workspaceGuard.workspace().resolve(PROJECT_LOCAL_PATH)).absent()) {
                return SettingsSourceLoadResult.missing();
            }
        } catch (UnsafeFileException exception) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.UNSAFE_FILE);
        } catch (IOException exception) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.UNREADABLE_FILE);
        }
        try {
            workspaceGuard.requireRegularFile(PROJECT_LOCAL_PATH);
        } catch (WorkspaceAccessException exception) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.UNSAFE_FILE);
        }
        SettingsLocalGitIgnorePolicy.Verification verification = localGitIgnorePolicy
                .verifyFixedLocalSettings(cancellationToken);
        if (verification == SettingsLocalGitIgnorePolicy.Verification.CANCELLED) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.CANCELLED);
        }
        if (verification == SettingsLocalGitIgnorePolicy.Verification.NOT_IGNORED) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.LOCAL_NOT_GITIGNORED);
        }
        if (verification != SettingsLocalGitIgnorePolicy.Verification.IGNORED) {
            return failure(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID,
                    ConfigurationDiagnosticCode.LOCAL_GIT_CHECK_FAILED);
        }
        return loadProject(SettingsSourceKind.PROJECT_LOCAL, PROJECT_LOCAL_SAFE_ID, PROJECT_LOCAL_PATH, cancellationToken, () -> { });
    }

    SettingsSourceLoadResult loadUser(CancellationToken cancellationToken, Runnable afterRead) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(afterRead, "afterRead 不能为空");
        SettingsSourceId sourceId = sourceId(SettingsSourceKind.USER, USER_SAFE_ID);
        if (cancellationToken.isCancellationRequested()) return failure(sourceId, ConfigurationDiagnosticCode.CANCELLED);
        try {
            if (probeOptional(userRoot).absent()) return SettingsSourceLoadResult.missing();
            RootSnapshot beforeRoot = verifyUserRoot();
            if (probeOptional(userTarget).absent()) return SettingsSourceLoadResult.missing();
            FileSnapshot before = verifyUserTarget(beforeRoot);
            return parseStable(sourceId, userTarget, before, cancellationToken, afterRead, () -> {
                RootSnapshot afterRoot = verifyUserRoot();
                if (!beforeRoot.sameIdentity(afterRoot)) throw new IdentityChangedException();
                return verifyUserTarget(afterRoot);
            });
        } catch (UnsafeFileException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNSAFE_FILE);
        } catch (IOException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNREADABLE_FILE);
        }
    }

    SettingsSourceLoadResult loadProject(SettingsSourceKind kind, String safeId, String fixedPath,
                                         CancellationToken cancellationToken, Runnable afterRead) {
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        Objects.requireNonNull(afterRead, "afterRead 不能为空");
        SettingsSourceId sourceId = sourceId(kind, safeId);
        if (cancellationToken.isCancellationRequested()) return failure(sourceId, ConfigurationDiagnosticCode.CANCELLED);
        try {
            Path logical = workspaceGuard.workspace().resolve(fixedPath).normalize();
            if (probeOptional(logical).absent()) return SettingsSourceLoadResult.missing();
            ValidatedWorkspacePath validated = workspaceGuard.requireRegularFile(fixedPath);
            FileSnapshot before = snapshot(validated.realPath());
            return parseStable(sourceId, validated.realPath(), before, cancellationToken, afterRead,
                    () -> snapshot(workspaceGuard.requireRegularFile(fixedPath).realPath()));
        } catch (UnsafeFileException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNSAFE_FILE);
        } catch (WorkspaceAccessException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNSAFE_FILE);
        } catch (IOException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNREADABLE_FILE);
        }
    }

    private SettingsSourceLoadResult parseStable(SettingsSourceId sourceId, Path target, FileSnapshot before,
                                                  CancellationToken token, Runnable afterRead,
                                                  SnapshotSupplier afterSnapshot) {
        try {
            if (before.size() > SettingsV1SourceParser.MAX_BYTES) return failure(sourceId, ConfigurationDiagnosticCode.BYTE_LIMIT);
            byte[] bytes = readNoFollow(target, before.size());
            afterRead.run();
            if (token.isCancellationRequested()) return failure(sourceId, ConfigurationDiagnosticCode.CANCELLED);
            FileSnapshot after = afterSnapshot.get();
            if (after.size() > SettingsV1SourceParser.MAX_BYTES) {
                return failure(sourceId, ConfigurationDiagnosticCode.BYTE_LIMIT);
            }
            if (!before.sameIdentity(after)) return failure(sourceId, ConfigurationDiagnosticCode.IDENTITY_CHANGED);
            if (!isStrictUtf8WithoutNul(bytes)) return failure(sourceId, ConfigurationDiagnosticCode.MALFORMED_JSON);
            SettingsV1SourceParser.ParseResult parsed = parser.parse(sourceId, bytes);
            return new SettingsSourceLoadResult(parsed.snapshot(), parsed.diagnostics());
        } catch (IdentityChangedException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.IDENTITY_CHANGED);
        } catch (UnsafeFileException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNSAFE_FILE);
        } catch (ByteLimitException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.BYTE_LIMIT);
        } catch (IOException | WorkspaceAccessException exception) {
            return failure(sourceId, ConfigurationDiagnosticCode.UNREADABLE_FILE);
        }
    }

    private static OptionalProbe probeOptional(Path path) throws IOException, UnsafeFileException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new UnsafeFileException();
            return OptionalProbe.present();
        } catch (NoSuchFileException exception) {
            return OptionalProbe.missing();
        }
    }

    private RootSnapshot verifyUserRoot() throws IOException, UnsafeFileException {
        BasicFileAttributes attributes = requireNoLink(userRoot);
        if (!attributes.isDirectory()) throw new UnsafeFileException();
        Path real = userRoot.toRealPath();
        if (!real.equals(userRoot.toAbsolutePath().normalize())) throw new UnsafeFileException();
        return new RootSnapshot(real, attributes.fileKey());
    }

    private FileSnapshot verifyUserTarget(RootSnapshot root) throws IOException, UnsafeFileException {
        FileSnapshot snapshot = snapshot(userTarget);
        if (!snapshot.realPath().startsWith(root.realPath())) throw new UnsafeFileException();
        return snapshot;
    }

    private static FileSnapshot snapshot(Path path) throws IOException, UnsafeFileException {
        BasicFileAttributes attributes = requireNoLink(path);
        if (!attributes.isRegularFile()) throw new UnsafeFileException();
        Path real = path.toRealPath();
        if (!real.equals(path.toAbsolutePath().normalize())) throw new UnsafeFileException();
        return new FileSnapshot(real, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private static BasicFileAttributes requireNoLink(Path path) throws IOException, UnsafeFileException {
        if (Files.isSymbolicLink(path)) throw new UnsafeFileException();
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || !path.toAbsolutePath().normalize().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new UnsafeFileException();
        }
        return attributes;
    }

    private static byte[] readNoFollow(Path target, long expectedSize) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = new ByteArrayOutputStream((int) expectedSize)) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if (output.size() + buffer.remaining() > SettingsV1SourceParser.MAX_BYTES) {
                    throw new ByteLimitException();
                }
                output.write(buffer.array(), buffer.position(), buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static boolean isStrictUtf8WithoutNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static SettingsSourceId sourceId(SettingsSourceKind kind, String safeId) {
        return new SettingsSourceId(kind, safeId);
    }

    private static SettingsSourceLoadResult failure(SettingsSourceKind kind, String safeId, ConfigurationDiagnosticCode code) {
        return failure(sourceId(kind, safeId), code);
    }

    private static SettingsSourceLoadResult failure(SettingsSourceId sourceId, ConfigurationDiagnosticCode code) {
        return new SettingsSourceLoadResult(Optional.empty(), List.of(new ConfigurationDiagnostic(sourceId, code,
                ConfigurationDiagnosticSeverity.ERROR, Optional.empty())));
    }

    /**
     * 固定来源一次读取的原子结果；缺失 optional 文件为无快照且无诊断。
     *
     * @param snapshot 仅在完整读取和严格解析成功时存在的快照
     * @param diagnostics 不含路径、字节内容或敏感字段值的失败分类
     */
    public record SettingsSourceLoadResult(Optional<SettingsSourceSnapshot> snapshot,
                                           List<ConfigurationDiagnostic> diagnostics) {
        /**
         * 建立不可变原子结果，禁止成功快照与失败诊断混合。
         *
         * @param snapshot 已完整解析的来源快照
         * @param diagnostics 安全诊断列表
         */
        public SettingsSourceLoadResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
            if (snapshot.isPresent() && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("成功快照不能携带诊断");
            }
        }

        static SettingsSourceLoadResult missing() {
            return new SettingsSourceLoadResult(Optional.empty(), List.of());
        }

        @Override
        public String toString() {
            return "SettingsSourceLoadResult[snapshot=" + (snapshot.isPresent() ? "<loaded>" : "<empty>")
                    + ", diagnostics=" + diagnostics + "]";
        }
    }

    private record OptionalProbe(boolean absent) {
        private static OptionalProbe present() {
            return new OptionalProbe(false);
        }

        private static OptionalProbe missing() {
            return new OptionalProbe(true);
        }
    }

    private record RootSnapshot(Path realPath, Object fileKey) {
        boolean sameIdentity(RootSnapshot other) {
            return realPath.equals(other.realPath()) && Objects.equals(fileKey, other.fileKey());
        }
    }

    private record FileSnapshot(Path realPath, Object fileKey, long size, java.nio.file.attribute.FileTime lastModifiedTime) {
        boolean sameIdentity(FileSnapshot other) {
            return realPath.equals(other.realPath()) && Objects.equals(fileKey, other.fileKey())
                    && size == other.size() && lastModifiedTime.equals(other.lastModifiedTime());
        }
    }

    @FunctionalInterface
    private interface SnapshotSupplier {
        FileSnapshot get() throws IOException, WorkspaceAccessException, UnsafeFileException;
    }

    private static final class UnsafeFileException extends Exception { }

    private static final class IdentityChangedException extends IOException { }

    private static final class ByteLimitException extends IOException { }
}
