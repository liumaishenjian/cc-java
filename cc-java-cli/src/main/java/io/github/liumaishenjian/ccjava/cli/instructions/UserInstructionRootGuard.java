package io.github.liumaishenjian.ccjava.cli.instructions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;

/**
 * 读取固定用户级 {@code AGENTS.md} 的独立安全边界。
 *
 * <p>该 Guard 只从启动时已解析的 {@code user.home} 推导
 * {@code .cc-java/instructions/AGENTS.md}，不接受任何调用方给出的文件名或路径。
 * User instructions 位于 Workspace 外，故不能用 {@code WorkspaceGuard} 代替本类型。
 * 失败一律返回无正文诊断，既不泄露 home/绝对路径，也不阻塞其他来源的指令。</p>
 *
 * @since 0.8.0
 */
public final class UserInstructionRootGuard {

    /** 固定单文件 UTF-8 字节上限。 */
    public static final int MAX_BYTES = 32 * 1024;
    /** 固定单文件文本行上限。 */
    public static final int MAX_LINES = 1_000;
    /** 固定逻辑目标路径 Unicode code point 上限。 */
    public static final int MAX_PATH_CODE_POINTS = 240;

    private static final String ROOT_DIRECTORY = ".cc-java/instructions";
    private static final String FIXED_FILE_NAME = "AGENTS.md";
    private static final String SAFE_SOURCE_ID = "user-instructions";

    private final Path root;
    private final Path target;

    /**
     * 根据 Composition Root 已解析一次的 home 固定唯一允许位置。
     *
     * @param userHome 启动时读取的用户 home；不能由模型、Settings 或仓库文本提供
     */
    public UserInstructionRootGuard(Path userHome) {
        Path home = Objects.requireNonNull(userHome, "userHome 不能为空").toAbsolutePath().normalize();
        this.root = home.resolve(ROOT_DIRECTORY).normalize();
        this.target = root.resolve(FIXED_FILE_NAME).normalize();
    }

    /**
     * 加载固定目标，并在读取前后重新验证 root、target、realpath 与文件 identity。
     *
     * @return 成功时含有正文；失败或不存在时只含安全诊断
     */
    public UserInstructionLoadResult load() {
        return load(() -> { });
    }

    UserInstructionLoadResult load(Runnable afterRead) {
        Objects.requireNonNull(afterRead, "afterRead 不能为空");
        if (pathCodePoints(target) > MAX_PATH_CODE_POINTS) {
            return rejected(UserInstructionDiagnostic.PATH_TOO_LONG);
        }
        try {
            RootSnapshot beforeRoot = verifyRoot();
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return UserInstructionLoadResult.missing();
            }
            FileSnapshot beforeFile = verifyTarget(beforeRoot);
            if (beforeFile.size() > MAX_BYTES) {
                return rejected(UserInstructionDiagnostic.BYTE_LIMIT);
            }

            byte[] bytes = readNoFollow(beforeFile);
            afterRead.run();
            if (bytes.length > MAX_BYTES) {
                return rejected(UserInstructionDiagnostic.BYTE_LIMIT);
            }
            String text = decodeUtf8WithoutNul(bytes);
            if (lineCount(text) > MAX_LINES) {
                return rejected(UserInstructionDiagnostic.LINE_LIMIT);
            }

            RootSnapshot afterRoot = verifyRoot();
            FileSnapshot afterFile = verifyTarget(afterRoot);
            if (!beforeRoot.sameIdentity(afterRoot) || !beforeFile.sameIdentity(afterFile)) {
                return rejected(UserInstructionDiagnostic.IDENTITY_CHANGED);
            }
            return UserInstructionLoadResult.loaded(text);
        } catch (SecurityFailure exception) {
            return rejected(exception.diagnostic());
        } catch (IOException exception) {
            return rejected(UserInstructionDiagnostic.UNREADABLE);
        }
    }

    private RootSnapshot verifyRoot() throws IOException, SecurityFailure {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityFailure(UserInstructionDiagnostic.ROOT_UNAVAILABLE);
        }
        BasicFileAttributes attributes;
        try {
            attributes = InstructionPathSafety.requireNoLink(root);
        } catch (InstructionPathSafety.UnsafeInstructionPathException exception) {
            throw new SecurityFailure(UserInstructionDiagnostic.ROOT_LINK_OR_TYPE);
        }
        if (!attributes.isDirectory()) {
            throw new SecurityFailure(UserInstructionDiagnostic.ROOT_LINK_OR_TYPE);
        }
        Path realRoot = root.toRealPath();
        if (!realRoot.equals(root.toAbsolutePath().normalize())) {
            throw new SecurityFailure(UserInstructionDiagnostic.ROOT_LINK_OR_TYPE);
        }
        return new RootSnapshot(realRoot, attributes.fileKey());
    }

    private FileSnapshot verifyTarget(RootSnapshot rootSnapshot) throws IOException, SecurityFailure {
        BasicFileAttributes attributes;
        try {
            attributes = InstructionPathSafety.requireNoLink(target);
        } catch (InstructionPathSafety.UnsafeInstructionPathException exception) {
            throw new SecurityFailure(UserInstructionDiagnostic.TARGET_LINK_OR_TYPE);
        }
        if (!attributes.isRegularFile()) {
            throw new SecurityFailure(UserInstructionDiagnostic.TARGET_LINK_OR_TYPE);
        }
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(rootSnapshot.realPath())) {
            throw new SecurityFailure(UserInstructionDiagnostic.LINK_ESCAPE);
        }
        if (!realTarget.equals(target.toAbsolutePath().normalize())) {
            throw new SecurityFailure(UserInstructionDiagnostic.TARGET_LINK_OR_TYPE);
        }
        return new FileSnapshot(realTarget, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private byte[] readNoFollow(FileSnapshot snapshot) throws IOException, SecurityFailure {
        try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = new ByteArrayOutputStream((int) snapshot.size())) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4 * 1024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if (output.size() + buffer.remaining() > MAX_BYTES) {
                    throw new SecurityFailure(UserInstructionDiagnostic.BYTE_LIMIT);
                }
                output.write(buffer.array(), buffer.position(), buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8WithoutNul(byte[] bytes) throws SecurityFailure {
        for (byte value : bytes) {
            if (value == 0) {
                throw new SecurityFailure(UserInstructionDiagnostic.NUL_BYTE);
            }
        }
        int offset = bytes.length >= 3
                && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF
                ? 3 : 0;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new SecurityFailure(UserInstructionDiagnostic.INVALID_UTF8);
        }
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

    private static int pathCodePoints(Path path) {
        String value = path.toString();
        return value.codePointCount(0, value.length());
    }

    private static UserInstructionLoadResult rejected(UserInstructionDiagnostic diagnostic) {
        return UserInstructionLoadResult.rejected(diagnostic);
    }

    private record RootSnapshot(Path realPath, Object fileKey) {
        private boolean sameIdentity(RootSnapshot other) {
            return realPath.equals(other.realPath) && Objects.equals(fileKey, other.fileKey);
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

    private static final class SecurityFailure extends Exception {
        private final UserInstructionDiagnostic diagnostic;

        private SecurityFailure(UserInstructionDiagnostic diagnostic) {
            this.diagnostic = diagnostic;
        }

        private UserInstructionDiagnostic diagnostic() {
            return diagnostic;
        }
    }

    /**
     * 不携带路径的读取结果；成功正文只供后续受控投影使用。
     *
     * @param text 成功读取的有界正文；失败时为空
     * @param diagnostic 固定安全诊断；成功时为空
     */
    public record UserInstructionLoadResult(Optional<String> text, Optional<UserInstructionDiagnostic> diagnostic) {
        /**
         * 固化互斥的成功正文或失败类别。
         *
         * @param text 成功读取的正文；失败时为空
         * @param diagnostic 失败的封闭类别；成功时为空
         */
        public UserInstructionLoadResult {
            text = Objects.requireNonNull(text, "text 不能为空");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic 不能为空");
            if (text.isPresent() == diagnostic.isPresent()) {
                throw new IllegalArgumentException("结果必须恰有正文或诊断");
            }
        }

        private static UserInstructionLoadResult loaded(String text) {
            return new UserInstructionLoadResult(Optional.of(text), Optional.empty());
        }

        private static UserInstructionLoadResult missing() {
            return rejected(UserInstructionDiagnostic.MISSING);
        }

        private static UserInstructionLoadResult rejected(UserInstructionDiagnostic diagnostic) {
            return new UserInstructionLoadResult(Optional.empty(), Optional.of(diagnostic));
        }

        /** 不将成功正文、路径或异常细节回显到日志或异常文本。 */
        @Override
        public String toString() {
            return diagnostic.map(value -> "UserInstructionLoadResult[diagnostic=" + value + "]")
                    .orElse("UserInstructionLoadResult[loaded]");
        }
    }

    /** 用户级固定指令读取的封闭失败类别；不含任意文本、路径或正文。 */
    public enum UserInstructionDiagnostic {
        /** 固定目标不存在。 */
        MISSING,
        /** 推导后的固定目标路径超过上限。 */
        PATH_TOO_LONG,
        /** 用户级根目录缺失或不可访问。 */
        ROOT_UNAVAILABLE,
        /** 用户级根目录是链接或不是目录。 */
        ROOT_LINK_OR_TYPE,
        /** 固定目标是链接或不是普通文件。 */
        TARGET_LINK_OR_TYPE,
        /** 真实目标不在已验证根目录内。 */
        LINK_ESCAPE,
        /** 单文件字节数超过上限。 */
        BYTE_LIMIT,
        /** 单文件行数超过上限。 */
        LINE_LIMIT,
        /** 读取前后 root 或目标 identity 改变。 */
        IDENTITY_CHANGED,
        /** 内容含 NUL 字节。 */
        NUL_BYTE,
        /** 内容不是严格 UTF-8。 */
        INVALID_UTF8,
        /** 其他无法安全分类的读取失败。 */
        UNREADABLE
    }
}
