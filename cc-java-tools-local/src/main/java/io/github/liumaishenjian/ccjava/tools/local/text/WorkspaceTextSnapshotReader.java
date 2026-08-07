package io.github.liumaishenjian.ccjava.tools.local.text;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把一个已验证的普通文件整体读成 {@link WorkspaceTextSnapshot}。
 *
 * <p>只负责一次字节读取、严格 UTF-8 解码、NUL 拒绝与换行分类；不校验 Workspace 路径、
 * 文件身份或调用者权限。需要精确旧内容前置条件的写工具使用本读取器，因为它必须同时
 * 掌握整份内容与原始字节；只需要一页文本的读取工具应当使用
 * {@link BoundedTextRangeReader}，避免为一页内容加载整个文件。</p>
 *
 * @since 0.8.0
 */
public final class WorkspaceTextSnapshotReader {

    private WorkspaceTextSnapshotReader() {
    }

    /**
     * 读取整份文本快照。
     *
     * @param path 调用方已验证的普通文件真实路径
     * @param maximumBytes 允许读取的最大字节数
     * @return 同时携带规范文本、原始字节、BOM 与分隔符风格的快照
     * @throws WorkspaceAccessException 文件超限、不是严格 UTF-8 文本或读取失败时
     */
    public static WorkspaceTextSnapshot read(Path path, long maximumBytes)
            throws WorkspaceAccessException {
        if (maximumBytes <= 0 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes 必须在 (0, Integer.MAX_VALUE) 内");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取目标文件");
        }
        if (size > maximumBytes || size > Integer.MAX_VALUE) {
            throw error(ToolErrorCode.FILE_TOO_LARGE, "文件超过读取大小上限");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            // size 只用于快速拒绝，不能作为安全上限：文件可能在 size 与 read 之间增长。
            // 最多读取 ceiling + 1 个字节，用额外字节可靠地区分“刚好达到上限”和“已超限”。
            bytes = input.readNBytes(Math.toIntExact(maximumBytes + 1));
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取目标文件");
        }
        if (bytes.length > maximumBytes) {
            throw error(ToolErrorCode.FILE_TOO_LARGE, "文件超过读取大小上限");
        }
        return of(bytes);
    }

    /**
     * 从已在内存中的字节构造快照，供写工具复用刚落盘的内容。
     *
     * @param bytes 完整 UTF-8 字节
     * @return 文本快照
     * @throws WorkspaceAccessException 含 NUL 或不是严格 UTF-8 时
     */
    public static WorkspaceTextSnapshot of(byte[] bytes) throws WorkspaceAccessException {
        for (byte value : bytes) {
            if (value == 0) {
                throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是受支持的 UTF-8 文本");
            }
        }
        boolean bom = bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
        int offset = bom ? 3 : 0;
        String rawText;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            rawText = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是有效 UTF-8 文本");
        }
        return canonicalize(rawText, bytes, bom);
    }

    private static WorkspaceTextSnapshot canonicalize(
            String rawText,
            byte[] bytes,
            boolean bom) {
        int length = rawText.length();
        StringBuilder canonical = new StringBuilder(length);
        int[] collapsed = new int[16];
        int collapsedCount = 0;
        boolean sawLf = false;
        boolean sawCrLf = false;
        boolean sawBareCr = false;
        for (int index = 0; index < length; index++) {
            char current = rawText.charAt(index);
            if (current == '\r') {
                boolean pair = index + 1 < length && rawText.charAt(index + 1) == '\n';
                if (pair) {
                    sawCrLf = true;
                    if (collapsedCount == collapsed.length) {
                        int[] grown = new int[collapsed.length * 2];
                        System.arraycopy(collapsed, 0, grown, 0, collapsedCount);
                        collapsed = grown;
                    }
                    collapsed[collapsedCount++] = canonical.length();
                    canonical.append('\n');
                    index++;
                } else {
                    sawBareCr = true;
                    canonical.append('\n');
                }
                continue;
            }
            if (current == '\n') {
                sawLf = true;
            }
            canonical.append(current);
        }
        int[] trimmed = new int[collapsedCount];
        System.arraycopy(collapsed, 0, trimmed, 0, collapsedCount);
        return new WorkspaceTextSnapshot(
                canonical.toString(),
                rawText,
                bytes,
                bom,
                classify(sawLf, sawCrLf, sawBareCr),
                trimmed);
    }

    private static LineSeparatorStyle classify(
            boolean sawLf,
            boolean sawCrLf,
            boolean sawBareCr) {
        if (sawBareCr) {
            return LineSeparatorStyle.MIXED;
        }
        if (sawLf && sawCrLf) {
            return LineSeparatorStyle.MIXED;
        }
        if (sawCrLf) {
            return LineSeparatorStyle.CRLF;
        }
        if (sawLf) {
            return LineSeparatorStyle.LF;
        }
        return LineSeparatorStyle.ABSENT;
    }

    private static WorkspaceAccessException error(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }
}
