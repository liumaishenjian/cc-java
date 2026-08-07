package io.github.liumaishenjian.ccjava.tools.local.text;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 以固定内存预算流式读取一个普通文件中的 1-based 行范围。
 *
 * <p>与整文件读取的关键区别是：本读取器<b>不会</b>为了返回一页而把整个文件读进内存，
 * 也不会把跳过的行保留下来。它按固定大小的字节窗口增量严格 UTF-8 解码，跨窗口正确处理
 * 被切断的多字节序列与被切断的 {@code \r\n}，只累积真正被选中的行，并在拿到本页之后
 * 最多再看一个字符来判定是否仍有后续内容。</p>
 *
 * <p>因此单次调用的内存占用由“页行数 × 单行字符预算”决定，而不是由文件大小决定；
 * 这使得在明确请求有界范围时，可以安全读取超过整文件读取 ceiling 的大文件，同时仍受
 * 独立的扫描字节 ceiling、单行字符预算、页字符预算、取消信号和调用方超时约束。</p>
 *
 * <p>本读取器不校验 Workspace 路径、不判断权限、不写文件；调用方必须先通过
 * WorkspaceGuard 得到真实路径。文件中出现 NUL 或非法 UTF-8 时立刻以结构化错误失败关闭。</p>
 *
 * @since 0.8.0
 */
public final class BoundedTextRangeReader {

    /** 单次字节窗口大小；跨窗口的多字节序列由 compact 保留。 */
    private static final int BYTE_WINDOW = 64 * 1024;

    /** 单次解码得到的字符窗口大小。 */
    private static final int CHAR_WINDOW = 16 * 1024;

    private final long scanCeilingBytes;
    private final int maximumLineCharacters;
    private final int maximumPageCharacters;

    /**
     * 创建带显式资源 ceiling 的范围读取器。
     *
     * @param scanCeilingBytes 单次调用允许扫描的最大字节数
     * @param maximumLineCharacters 单行允许保留的最大字符数，防止超长行无界累积
     * @param maximumPageCharacters 本页所有行合计允许保留的最大字符数
     */
    public BoundedTextRangeReader(
            long scanCeilingBytes,
            int maximumLineCharacters,
            int maximumPageCharacters) {
        if (scanCeilingBytes <= 0 || maximumLineCharacters <= 0 || maximumPageCharacters <= 0) {
            throw new IllegalArgumentException("范围读取 ceiling 必须为正数");
        }
        this.scanCeilingBytes = scanCeilingBytes;
        this.maximumLineCharacters = maximumLineCharacters;
        this.maximumPageCharacters = maximumPageCharacters;
    }

    /**
     * 读取一页行范围。
     *
     * @param path 调用方已验证的普通文件真实路径
     * @param startLine 1-based 起始行
     * @param maximumLines 本页最多返回的行数
     * @param cancellation 当前 Run 的取消信号
     * @return 有界读取结果；起始行超过文件末尾时返回空行且给出已知总行数
     * @throws WorkspaceAccessException 文件不是严格 UTF-8 文本、扫描超过 ceiling、
     *         被取消或读取失败时
     */
    public BoundedTextRange read(
            Path path,
            int startLine,
            int maximumLines,
            CancellationToken cancellation) throws WorkspaceAccessException {
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (startLine < 1 || maximumLines < 1) {
            throw new IllegalArgumentException("startLine 与 maximumLines 必须为正数");
        }
        Scan scan = new Scan(startLine, maximumLines);
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bytes = ByteBuffer.allocate(BYTE_WINDOW);
            CharBuffer chars = CharBuffer.allocate(CHAR_WINDOW);
            boolean endOfStream = false;
            while (!scan.stopped) {
                ensureNotCancelled(cancellation);
                long remainingBudget = scanCeilingBytes - scan.scannedBytes;
                if (remainingBudget <= 0) {
                    if (channel.position() == channel.size()) {
                        bytes.flip();
                        decode(decoder, bytes, chars, true, scan);
                        if (!scan.stopped) {
                            flush(decoder, chars, scan);
                            if (bytes.hasRemaining()) {
                                throw error(
                                        ToolErrorCode.UNSUPPORTED_ENCODING,
                                        "目标不是有效 UTF-8 文本");
                            }
                            scan.finishAtEndOfFile();
                        }
                        break;
                    }
                    scan.stopAtScanCeiling();
                    break;
                }
                int originalLimit = bytes.limit();
                int permitted = (int) Math.min(remainingBudget, bytes.remaining());
                bytes.limit(bytes.position() + permitted);
                int read = channel.read(bytes);
                bytes.limit(originalLimit);
                if (read < 0) {
                    endOfStream = true;
                } else {
                    scan.scannedBytes += read;
                }
                bytes.flip();
                decode(decoder, bytes, chars, endOfStream, scan);
                if (scan.stopped) {
                    break;
                }
                if (endOfStream) {
                    flush(decoder, chars, scan);
                    if (bytes.hasRemaining()) {
                        throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是有效 UTF-8 文本");
                    }
                    scan.finishAtEndOfFile();
                    break;
                }
                bytes.compact();
            }
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取目标文件");
        }
        if (scan.ceilingExhausted && scan.lines.isEmpty()) {
            throw error(
                    ToolErrorCode.FILE_TOO_LARGE,
                    "在允许的扫描字节预算内未能到达请求的起始行");
        }
        return scan.toRange();
    }

    private void decode(
            CharsetDecoder decoder,
            ByteBuffer bytes,
            CharBuffer chars,
            boolean endOfInput,
            Scan scan) throws WorkspaceAccessException {
        while (true) {
            chars.clear();
            CoderResult result = decoder.decode(bytes, chars, endOfInput);
            if (result.isError()) {
                throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是有效 UTF-8 文本");
            }
            chars.flip();
            scan.consume(chars, maximumLineCharacters, maximumPageCharacters);
            if (scan.rejectedNull) {
                throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是受支持的 UTF-8 文本");
            }
            if (scan.stopped || !result.isOverflow()) {
                return;
            }
        }
    }

    private void flush(CharsetDecoder decoder, CharBuffer chars, Scan scan)
            throws WorkspaceAccessException {
        while (true) {
            chars.clear();
            CoderResult result = decoder.flush(chars);
            if (result.isError()) {
                throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是有效 UTF-8 文本");
            }
            chars.flip();
            scan.consume(chars, maximumLineCharacters, maximumPageCharacters);
            if (scan.rejectedNull) {
                throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是受支持的 UTF-8 文本");
            }
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private static void ensureNotCancelled(CancellationToken cancellation)
            throws WorkspaceAccessException {
        if (cancellation.isCancellationRequested()) {
            throw error(ToolErrorCode.OPERATION_CANCELLED, "文件读取已取消");
        }
    }

    private static WorkspaceAccessException error(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }

    /**
     * 单次扫描的可变状态。
     *
     * <p>刻意保持为私有可变对象：跳过的行不会被保留，选中的行只保留到预算上限，
     * 因此峰值内存与文件大小无关。</p>
     */
    private static final class Scan {

        private final int startLine;
        private final int maximumLines;
        private final List<String> lines = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();

        private int currentLine = 1;
        private int pageCharacters;
        private int truncatedLines;
        private boolean currentTruncated;
        private boolean currentSelected;
        private boolean currentHasContent;
        private boolean pendingCarriageReturn;
        private boolean beforeFirstCharacter = true;
        private boolean probingForMore;
        private boolean hasMore;
        private boolean stopped;
        private boolean rejectedNull;
        private boolean endOfFile;
        private boolean ceilingExhausted;
        private boolean scanCeilingReached;
        private int nextStartLine;
        private long scannedBytes;
        private OptionalLong totalLines = OptionalLong.empty();

        private Scan(int startLine, int maximumLines) {
            this.startLine = startLine;
            this.maximumLines = maximumLines;
            this.currentSelected = startLine == 1;
        }

        private void consume(CharBuffer chars, int maximumLineCharacters, int maximumPageCharacters) {
            while (chars.hasRemaining()) {
                char value = chars.get();
                if (value == 0) {
                    rejectedNull = true;
                    stopped = true;
                    return;
                }
                if (beforeFirstCharacter) {
                    beforeFirstCharacter = false;
                    if (value == '\uFEFF') {
                        // 首字符 BOM 只是编码外观，不属于第一行正文。
                        continue;
                    }
                }
                if (pendingCarriageReturn) {
                    pendingCarriageReturn = false;
                    if (value == '\n') {
                        endLine(maximumPageCharacters);
                        if (stopped) {
                            return;
                        }
                        continue;
                    }
                    endLine(maximumPageCharacters);
                    if (stopped) {
                        return;
                    }
                }
                if (value == '\r') {
                    // 裸 \r 与 \r\n 都是行边界；具体属于哪一种要等下一个字符才能确定。
                    pendingCarriageReturn = true;
                    continue;
                }
                if (probingForMore) {
                    // 已经拿到整页，只需要确认后面确实还有内容。
                    hasMore = true;
                    stopped = true;
                    return;
                }
                if (value == '\n') {
                    endLine(maximumPageCharacters);
                    if (stopped) {
                        return;
                    }
                    continue;
                }
                // 无论是否被选中都要记住“本行有内容”，否则文件末尾未选中的最后一行
                // 会被漏计，进而报出错误的总行数。
                currentHasContent = true;
                if (currentSelected) {
                    if (current.length() < maximumLineCharacters) {
                        current.append(value);
                    } else {
                        currentTruncated = true;
                    }
                }
            }
        }

        private void endLine(int maximumPageCharacters) {
            if (probingForMore) {
                // 整页已满后又出现一个行边界，说明确实还有后续行。
                hasMore = true;
                stopped = true;
                return;
            }
            if (currentSelected) {
                lines.add(current.toString());
                pageCharacters += current.length();
                if (currentTruncated) {
                    truncatedLines++;
                }
            }
            current.setLength(0);
            currentTruncated = false;
            currentHasContent = false;
            currentLine++;
            currentSelected = currentLine >= startLine;
            if (currentSelected && lines.size() >= maximumLines) {
                probingForMore = true;
                nextStartLine = currentLine;
                return;
            }
            if (currentSelected && pageCharacters >= maximumPageCharacters) {
                // 页字符预算是内存保护；渲染层还会按最终可见正文做一次精确预算。
                hasMore = true;
                nextStartLine = currentLine;
                stopped = true;
            }
        }

        private void finishAtEndOfFile() {
            if (pendingCarriageReturn) {
                // 文件以裸 \r 结束：它仍然是一个行边界。
                pendingCarriageReturn = false;
                if (probingForMore) {
                    hasMore = true;
                    stopped = true;
                    return;
                }
                endLine(Integer.MAX_VALUE);
                if (stopped) {
                    return;
                }
            } else if (currentHasContent) {
                // 文件不以分隔符结束：最后一行仍然是一整行，即使它没有被选中。
                if (probingForMore) {
                    hasMore = true;
                    stopped = true;
                    return;
                }
                if (currentSelected) {
                    lines.add(current.toString());
                    if (currentTruncated) {
                        truncatedLines++;
                    }
                }
                current.setLength(0);
                currentHasContent = false;
                currentLine++;
            }
            endOfFile = true;
            hasMore = false;
            totalLines = OptionalLong.of(currentLine - 1L);
            stopped = true;
        }

        private void stopAtScanCeiling() {
            scanCeilingReached = true;
            stopped = true;
            if (lines.isEmpty()) {
                ceilingExhausted = true;
                return;
            }
            hasMore = true;
            if (nextStartLine <= 0) {
                nextStartLine = currentLine;
            }
        }

        private BoundedTextRange toRange() {
            int resolvedNextStart = hasMore
                    ? (nextStartLine > 0 ? nextStartLine : currentLine)
                    : 0;
            return new BoundedTextRange(
                    startLine,
                    lines,
                    hasMore,
                    resolvedNextStart,
                    hasMore ? OptionalLong.empty() : totalLines,
                    endOfFile ? OptionalLong.of(scannedBytes) : OptionalLong.empty(),
                    truncatedLines,
                    scanCeilingReached);
        }
    }
}
