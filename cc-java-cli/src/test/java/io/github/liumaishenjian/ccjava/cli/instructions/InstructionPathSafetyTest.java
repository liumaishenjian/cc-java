package io.github.liumaishenjian.ccjava.cli.instructions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionPathSafetyTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsOtherReparseSymbolicLinkRealpathMismatchAndIoUncertainty() {
        Path path = temporary.resolve("candidate");
        BasicFileAttributes regular = attributes(false, false);

        assertThatThrownBy(() -> InstructionPathSafety.requireNoLink(path,
                access(true, regular, path.toAbsolutePath())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InstructionPathSafety.requireNoLink(path,
                access(false, attributes(true, false), path.toAbsolutePath())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InstructionPathSafety.requireNoLink(path,
                access(false, attributes(false, true), path.toAbsolutePath())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InstructionPathSafety.requireNoLink(path,
                access(false, regular, temporary.resolve("elsewhere").toAbsolutePath())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> InstructionPathSafety.requireNoLink(path, new InstructionPathSafety.FileAccess() {
            @Override
            public boolean isSymbolicLink(Path ignored) {
                return false;
            }

            @Override
            public BasicFileAttributes attributes(Path ignored) throws IOException {
                throw new IOException("attributes unavailable");
            }

            @Override
            public Path noFollowRealPath(Path ignored) {
                throw new AssertionError("must not resolve after attribute uncertainty");
            }
        })).isInstanceOf(IOException.class);
    }

    private static InstructionPathSafety.FileAccess access(
            boolean symbolicLink, BasicFileAttributes attributes, Path noFollowRealPath) {
        return new InstructionPathSafety.FileAccess() {
            @Override
            public boolean isSymbolicLink(Path ignored) {
                return symbolicLink;
            }

            @Override
            public BasicFileAttributes attributes(Path ignored) {
                return attributes;
            }

            @Override
            public Path noFollowRealPath(Path ignored) {
                return noFollowRealPath;
            }
        };
    }

    private static BasicFileAttributes attributes(boolean other, boolean symbolicLink) {
        return new BasicFileAttributes() {
            @Override public FileTime lastModifiedTime() { return FileTime.fromMillis(0); }
            @Override public FileTime lastAccessTime() { return FileTime.fromMillis(0); }
            @Override public FileTime creationTime() { return FileTime.fromMillis(0); }
            @Override public boolean isRegularFile() { return true; }
            @Override public boolean isDirectory() { return false; }
            @Override public boolean isSymbolicLink() { return symbolicLink; }
            @Override public boolean isOther() { return other; }
            @Override public long size() { return 0; }
            @Override public Object fileKey() { return "fake"; }
        };
    }
}
