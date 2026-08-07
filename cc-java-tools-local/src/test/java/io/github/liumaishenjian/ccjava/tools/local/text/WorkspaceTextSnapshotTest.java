package io.github.liumaishenjian.ccjava.tools.local.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTextSnapshotTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path workspace;

    @Test
    void canonicalizesCrlfForModelWhileKeepingRawBytes() throws Exception {
        Path file = write("crlf.txt", "alpha\r\nbeta\r\n", false);

        WorkspaceTextSnapshot snapshot = WorkspaceTextSnapshotReader.read(file, 1024);

        assertThat(snapshot.canonicalText()).isEqualTo("alpha\nbeta\n");
        assertThat(snapshot.separatorStyle()).isEqualTo(LineSeparatorStyle.CRLF);
        assertThat(snapshot.byteOrderMark()).isFalse();
        assertThat(snapshot.rawBytes())
                .isEqualTo("alpha\r\nbeta\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.lineCount()).isEqualTo(2);
    }

    @Test
    void roundTripsUniformCrlfExactlyWhenReplacingMultipleLines() throws Exception {
        Path file = write("patch.txt", "head\r\nfirst\r\nsecond\r\ntail\r\n", true);
        WorkspaceTextSnapshot snapshot = WorkspaceTextSnapshotReader.read(file, 1024);

        byte[] updated = snapshot.replaceBytes("first\nsecond", "one\ntwo\nthree", false);

        assertThat(updated).startsWith(BOM);
        assertThat(new String(updated, 3, updated.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("head\r\none\r\ntwo\r\nthree\r\ntail\r\n");
    }

    @Test
    void roundTripsUniformLfExactly() throws Exception {
        Path file = write("lf.txt", "head\nfirst\ntail\n", false);
        WorkspaceTextSnapshot snapshot = WorkspaceTextSnapshotReader.read(file, 1024);

        byte[] updated = snapshot.replaceBytes("first", "changed\nadded", false);

        assertThat(new String(updated, StandardCharsets.UTF_8))
                .isEqualTo("head\nchanged\nadded\ntail\n");
        assertThat(snapshot.separatorStyle()).isEqualTo(LineSeparatorStyle.LF);
    }

    @Test
    void classifiesMixedAndBareCarriageReturnAsUnwritableForMultilineEdits() throws Exception {
        Path mixed = write("mixed.txt", "a\r\nb\nc\r\n", false);
        Path bare = write("bare.txt", "a\rb\rc", false);

        WorkspaceTextSnapshot mixedSnapshot = WorkspaceTextSnapshotReader.read(mixed, 1024);
        WorkspaceTextSnapshot bareSnapshot = WorkspaceTextSnapshotReader.read(bare, 1024);

        assertThat(mixedSnapshot.separatorStyle()).isEqualTo(LineSeparatorStyle.MIXED);
        assertThat(bareSnapshot.separatorStyle()).isEqualTo(LineSeparatorStyle.MIXED);
        assertThat(mixedSnapshot.canonicalText()).isEqualTo("a\nb\nc\n");
        assertThat(bareSnapshot.canonicalText()).isEqualTo("a\nb\nc");
        assertThat(mixedSnapshot.canReplace("a\nb", "x\ny")).isFalse();
        assertThat(bareSnapshot.canReplace("b", "x\ny")).isFalse();
    }

    @Test
    void replacesSingleLineFragmentInMixedFileWithoutRewritingUnrelatedSeparators()
            throws Exception {
        Path file = write("mixed-safe.txt", "a\r\nbee\nc\r\n", false);
        WorkspaceTextSnapshot snapshot = WorkspaceTextSnapshotReader.read(file, 1024);

        assertThat(snapshot.canReplace("bee", "cee")).isTrue();
        byte[] updated = snapshot.replaceBytes("bee", "cee", false);

        assertThat(new String(updated, StandardCharsets.UTF_8)).isEqualTo("a\r\ncee\nc\r\n");
    }

    @Test
    void rejectsNullBytesAndInvalidUtf8() throws Exception {
        Path binary = workspace.resolve("binary.bin");
        Files.write(binary, new byte[] {65, 0, 66});
        Path invalid = workspace.resolve("invalid.txt");
        Files.write(invalid, new byte[] {(byte) 0xC3, 0x28});

        assertThat(readError(binary)).isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
        assertThat(readError(invalid)).isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
    }

    @Test
    void enforcesByteCeilingDuringTheReadInsteadOfTrustingOnlyInitialSize() throws Exception {
        Path exact = Files.write(workspace.resolve("exact.txt"), new byte[] {1, 2, 3, 4});
        Path oversized = Files.write(workspace.resolve("oversized.txt"), new byte[] {1, 2, 3, 4, 5});

        assertThat(WorkspaceTextSnapshotReader.read(exact, 4).rawBytes()).hasSize(4);
        assertThat(readError(oversized, 4)).isEqualTo(ToolErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void reportsLineNumbersAndCanonicalLineSlices() throws Exception {
        Path file = write("lines.txt", "one\r\ntwo\r\nthree\r\n", false);
        WorkspaceTextSnapshot snapshot = WorkspaceTextSnapshotReader.read(file, 1024);

        assertThat(snapshot.lineNumberAt(snapshot.indexOf("two"))).isEqualTo(2);
        assertThat(snapshot.canonicalLines(2, 3)).isEqualTo("two\nthree");
        assertThat(snapshot.canonicalLines(1, 1)).isEqualTo("one");
        assertThat(snapshot.countOccurrences("two")).isEqualTo(1);
    }

    private ToolErrorCode readError(Path path) {
        return readError(path, 1024);
    }

    private ToolErrorCode readError(Path path, long maximumBytes) {
        try {
            WorkspaceTextSnapshotReader.read(path, maximumBytes);
            throw new AssertionError("期望读取失败");
        } catch (WorkspaceAccessException exception) {
            return exception.error().code();
        }
    }

    private Path write(String name, String body, boolean bom) throws Exception {
        Path file = workspace.resolve(name);
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            Files.write(file, content);
            return file;
        }
        byte[] bytes = new byte[content.length + 3];
        System.arraycopy(BOM, 0, bytes, 0, 3);
        System.arraycopy(content, 0, bytes, 3, content.length);
        Files.write(file, bytes);
        return file;
    }
}
