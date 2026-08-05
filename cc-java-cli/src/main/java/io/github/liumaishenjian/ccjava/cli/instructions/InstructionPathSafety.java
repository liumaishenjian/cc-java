package io.github.liumaishenjian.ccjava.cli.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Objects;

/**
 * Instructions Adapter 共用的 no-follow 路径安全检查。
 *
 * <p>Windows Junction/reparse point 可表现为 {@link BasicFileAttributes#isOther()}，故本类型拒绝
 * symlink、reparse、no-follow realpath 不一致及属性读取的不确定状态。调用方仍负责 containment、
 * 文件类型和读前后的 identity 验证。</p>
 *
 * @since 0.8.0
 */
final class InstructionPathSafety {
    private InstructionPathSafety() {
    }

    static BasicFileAttributes requireNoLink(Path path) throws IOException {
        return requireNoLink(path, new NioFileAccess());
    }

    // 同包接缝使 reparse、NOFOLLOW realpath 与 IO 不确定性无需依赖宿主文件系统即可证伪。
    static BasicFileAttributes requireNoLink(Path path, FileAccess access) throws IOException {
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(access, "access 不能为空");
        if (access.isSymbolicLink(path)) {
            throw new UnsafeInstructionPathException();
        }
        BasicFileAttributes attributes = access.attributes(path);
        if (attributes.isOther() || attributes.isSymbolicLink()) {
            throw new UnsafeInstructionPathException();
        }
        if (!path.toAbsolutePath().normalize().equals(access.noFollowRealPath(path))) {
            throw new UnsafeInstructionPathException();
        }
        return attributes;
    }

    static final class UnsafeInstructionPathException extends IOException {
        private UnsafeInstructionPathException() {
            super("unsafe instruction path");
        }
    }

    interface FileAccess {
        boolean isSymbolicLink(Path path);

        BasicFileAttributes attributes(Path path) throws IOException;

        Path noFollowRealPath(Path path) throws IOException;
    }

    private static final class NioFileAccess implements FileAccess {
        @Override
        public boolean isSymbolicLink(Path path) {
            return Files.isSymbolicLink(path);
        }

        @Override
        public BasicFileAttributes attributes(Path path) throws IOException {
            try {
                return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unsupported) {
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        }

        @Override
        public Path noFollowRealPath(Path path) throws IOException {
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
    }
}
