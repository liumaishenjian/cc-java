package io.github.liumaishenjian.ccjava.tools.local.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedTextRangeReaderTest {

    @TempDir
    Path workspace;

    private final BoundedTextRangeReader reader =
            new BoundedTextRangeReader(64L * 1024 * 1024, 4_000, 64_000);

    @Test
    void returnsRequestedRangeWithContinuationAndWithoutClaimingUnknownTotals()
            throws Exception {
        Path file = Files.writeString(workspace.resolve("page.txt"), "l1\nl2\nl3\nl4\nl5\n");

        BoundedTextRange page = reader.read(file, 2, 2, CancellationToken.none());

        assertThat(page.lines()).containsExactly("l2", "l3");
        assertThat(page.firstLine()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextStartLine()).isEqualTo(4);
        assertThat(page.totalLines()).isEmpty();
        assertThat(page.totalBytes()).isEmpty();
        assertThat(page.completeFile()).isFalse();
    }

    @Test
    void reportsTotalsOnlyWhenScanReachesEndOfFile() throws Exception {
        Path file = Files.writeString(workspace.resolve("small.txt"), "a\nb\nc\n");

        BoundedTextRange whole = reader.read(file, 1, 200, CancellationToken.none());

        assertThat(whole.lines()).containsExactly("a", "b", "c");
        assertThat(whole.hasMore()).isFalse();
        assertThat(whole.totalLines()).hasValue(3);
        assertThat(whole.totalBytes()).hasValue(6);
        assertThat(whole.completeFile()).isTrue();
    }

    @Test
    void doesNotReportPhantomTrailingLineForFileEndingWithSeparator() throws Exception {
        Path lf = Files.writeString(workspace.resolve("trail-lf.txt"), "a\nb\n");
        Path crlf = Files.write(
                workspace.resolve("trail-crlf.txt"),
                "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));

        BoundedTextRange lfPage = reader.read(lf, 1, 2, CancellationToken.none());
        BoundedTextRange crlfPage = reader.read(crlf, 1, 2, CancellationToken.none());

        assertThat(lfPage.lines()).containsExactly("a", "b");
        assertThat(lfPage.hasMore()).isFalse();
        assertThat(lfPage.totalLines()).hasValue(2);
        assertThat(crlfPage.lines()).containsExactly("a", "b");
        assertThat(crlfPage.hasMore()).isFalse();
        assertThat(crlfPage.totalLines()).hasValue(2);
    }

    @Test
    void reportsGenuineTrailingEmptyLineAsMoreContent() throws Exception {
        Path file = Files.writeString(workspace.resolve("empty-tail.txt"), "a\nb\n\n");

        BoundedTextRange page = reader.read(file, 1, 2, CancellationToken.none());

        assertThat(page.lines()).containsExactly("a", "b");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextStartLine()).isEqualTo(3);
    }

    @Test
    void skipsByteOrderMarkAndSplitsCrlfAcrossBufferBoundary() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 20_000; line++) {
            body.append("line-").append(line).append("\r\n");
        }
        byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[content.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(content, 0, bytes, 3, content.length);
        Path file = Files.write(workspace.resolve("bom-crlf.txt"), bytes);

        BoundedTextRange first = reader.read(file, 1, 1, CancellationToken.none());
        BoundedTextRange deep = reader.read(file, 19_999, 5, CancellationToken.none());

        assertThat(first.lines()).containsExactly("line-1");
        assertThat(deep.lines()).containsExactly("line-19999", "line-20000");
        assertThat(deep.hasMore()).isFalse();
        assertThat(deep.totalLines()).hasValue(20_000);
    }

    @Test
    void readsBoundedRangeFromFileLargerThanWholeFileCeilingWithoutWholeFileAllocation()
            throws Exception {
        // 该文件远大于整文件读取 ceiling（2 MiB）；有界范围读取必须仍然成功。
        Path file = workspace.resolve("huge.txt");
        long targetBytes = 6L * 1024 * 1024;
        long written = 0;
        int line = 0;
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(file))) {
            while (written < targetBytes) {
                byte[] row = ("row-" + (++line) + "-"
                        + "x".repeat(64) + "\n").getBytes(StandardCharsets.UTF_8);
                output.write(row);
                written += row.length;
            }
        }
        assertThat(Files.size(file)).isGreaterThan(2L * 1024 * 1024);

        long before = usedHeapBytes();
        BoundedTextRange page = reader.read(file, 2, 3, CancellationToken.none());
        long after = usedHeapBytes();

        assertThat(page.lines()).hasSize(3);
        assertThat(page.lines().getFirst()).startsWith("row-2-");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextStartLine()).isEqualTo(5);
        assertThat(page.totalLines()).isEmpty();
        // 若实现仍在整份读入，堆增长会与文件大小同量级；有界读取远低于该量级。
        assertThat(after - before).isLessThan(2L * 1024 * 1024);
    }

    @Test
    void failsClosedWhenStartLineIsBeyondScanCeiling() throws Exception {
        Path file = Files.writeString(
                workspace.resolve("ceiling.txt"), "a\n".repeat(100_000));
        BoundedTextRangeReader tiny = new BoundedTextRangeReader(4_096, 4_000, 64_000);

        WorkspaceAccessException failure = catchRead(tiny, file, 90_000, 10);

        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void returnsPageWithMoreFlagWhenCeilingIsReachedAfterCollectingRequestedLines()
            throws Exception {
        Path file = Files.writeString(
                workspace.resolve("ceiling-page.txt"), "a\n".repeat(100_000));
        BoundedTextRangeReader tiny = new BoundedTextRangeReader(4_096, 4_000, 64_000);

        BoundedTextRange page = tiny.read(file, 1, 3, CancellationToken.none());

        assertThat(page.lines()).containsExactly("a", "a", "a");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.totalLines()).isEmpty();
    }

    @Test
    void neverScansBeyondDeclaredByteCeiling() throws Exception {
        Path file = Files.writeString(workspace.resolve("exact-ceiling.txt"), "a\nb\nc\n");
        BoundedTextRangeReader tiny = new BoundedTextRangeReader(4, 4_000, 64_000);

        BoundedTextRange page = tiny.read(file, 1, 10, CancellationToken.none());

        assertThat(page.lines()).containsExactly("a", "b");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.scanCeilingReached()).isTrue();
        assertThat(page.totalBytes()).isEmpty();
    }

    @Test
    void acceptsAFileThatEndsExactlyAtTheScanCeiling() throws Exception {
        Path file = Files.writeString(workspace.resolve("ends-at-ceiling.txt"), "only");
        BoundedTextRangeReader exact = new BoundedTextRangeReader(4, 4_000, 64_000);

        BoundedTextRange page = exact.read(file, 1, 10, CancellationToken.none());

        assertThat(page.lines()).containsExactly("only");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.totalBytes()).hasValue(4);
        assertThat(page.totalLines()).hasValue(1);
    }

    @Test
    void rejectsInvalidUtf8SplitAcrossBufferBoundary() throws Exception {
        // 在窗口边界附近放一个被截断的多字节序列，确保跨窗口解码不会静默通过。
        byte[] filler = "a\n".repeat(40_000).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[filler.length + 2];
        System.arraycopy(filler, 0, bytes, 0, filler.length);
        bytes[filler.length] = (byte) 0xE4;
        bytes[filler.length + 1] = (byte) 0xB8;
        Path file = Files.write(workspace.resolve("split-invalid.txt"), bytes);

        WorkspaceAccessException failure = catchRead(reader, file, 39_999, 5);

        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
    }

    @Test
    void keepsValidMultiByteCharacterSplitAcrossBufferBoundary() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 30_000; line++) {
            body.append("行-").append(line).append('\n');
        }
        Path file = Files.write(
                workspace.resolve("split-valid.txt"),
                body.toString().getBytes(StandardCharsets.UTF_8));

        BoundedTextRange page = reader.read(file, 29_999, 2, CancellationToken.none());

        assertThat(page.lines()).containsExactly("行-29999", "行-30000");
    }

    @Test
    void rejectsNullByte() throws Exception {
        Path file = Files.write(workspace.resolve("binary-with-nul.txt"), new byte[] {65, 10, 0, 66});

        WorkspaceAccessException failure = catchRead(reader, file, 1, 10);

        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.UNSUPPORTED_ENCODING);
    }

    @Test
    void boundsSingleHugeLineInsteadOfAccumulatingItEntirely() throws Exception {
        Path file = Files.writeString(
                workspace.resolve("huge-line.txt"),
                "z".repeat(500_000) + "\nsecond\n");

        BoundedTextRange page = reader.read(file, 1, 2, CancellationToken.none());

        assertThat(page.lines().getFirst()).hasSize(4_000);
        assertThat(page.lines().get(1)).isEqualTo("second");
        assertThat(page.truncatedLines()).isEqualTo(1);
    }

    @Test
    void honoursCancellationBeforeReturningContent() throws Exception {
        Path file = Files.writeString(workspace.resolve("cancel.txt"), "a\nb\n");

        WorkspaceAccessException failure = catchRead(reader, file, 1, 2, cancelled());

        assertThat(failure.error().code()).isEqualTo(ToolErrorCode.OPERATION_CANCELLED);
    }

    @Test
    void reportsKnownTotalsWhenStartLineIsPastEndOfFile() throws Exception {
        Path file = Files.writeString(workspace.resolve("short.txt"), "only");

        BoundedTextRange page = reader.read(file, 5, 10, CancellationToken.none());

        assertThat(page.lines()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.totalLines()).hasValue(1);
    }

    @Test
    void readsEmptyFileAsZeroLines() throws Exception {
        Path file = Files.writeString(workspace.resolve("empty.txt"), "");

        BoundedTextRange page = reader.read(file, 1, 10, CancellationToken.none());

        assertThat(page.lines()).isEmpty();
        assertThat(page.totalLines()).hasValue(0);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void treatsBareCarriageReturnAsLineBoundary() throws Exception {
        Path file = Files.write(
                workspace.resolve("bare-cr.txt"),
                "a\rb\rc".getBytes(StandardCharsets.UTF_8));

        BoundedTextRange page = reader.read(file, 1, 10, CancellationToken.none());

        assertThat(page.lines()).containsExactly("a", "b", "c");
        assertThat(page.totalLines()).hasValue(3);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static WorkspaceAccessException catchRead(
            BoundedTextRangeReader reader,
            Path path,
            int startLine,
            int maxLines) {
        return catchRead(reader, path, startLine, maxLines, CancellationToken.none());
    }

    private static WorkspaceAccessException catchRead(
            BoundedTextRangeReader reader,
            Path path,
            int startLine,
            int maxLines,
            CancellationToken cancellation) {
        try {
            reader.read(path, startLine, maxLines, cancellation);
            throw new AssertionError("期望读取失败");
        } catch (WorkspaceAccessException exception) {
            return exception;
        }
    }

    private static CancellationToken cancelled() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            @Override
            public Registration onCancellation(Runnable action) {
                action.run();
                return () -> {
                };
            }
        };
    }
}
