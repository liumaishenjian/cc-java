package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以严格 UTF-8 和字节上限读取普通文本。
 *
 * <p>本工具只负责一次字节读取与解码，不验证 Workspace 路径、文件身份或调用者权限。
 * 安全 Adapter 必须在调用前后自行完成真实路径和 TOCTOU 验证。</p>
 *
 * @since 0.4.0
 */
public final class Utf8TextReader {

    private Utf8TextReader() {
    }

    /**
     * 读取一次文本快照。
     *
     * @param path 调用方已验证的普通文件路径
     * @param maximumBytes 允许读取的最大字节数
     * @return 严格 UTF-8 解码后的文本
     * @throws WorkspaceAccessException 文件无法读取、超过上限或不是 UTF-8 文本时
     */
    public static String read(Path path, long maximumBytes) throws WorkspaceAccessException {
        return readDocument(path, maximumBytes).text();
    }

    /**
     * 读取文本与原始字节，使写工具可以在提交前检测并发变化。
     *
     * @param path 已验证普通文件
     * @param maximumBytes 字节上限
     * @return 严格 UTF-8 文档快照
     * @throws WorkspaceAccessException 文件过大、编码非法或读取失败时
     */
    public static Utf8TextDocument readDocument(Path path, long maximumBytes)
            throws WorkspaceAccessException {
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
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw error(ToolErrorCode.EXECUTION_FAILED, "无法读取目标文件");
        }
        if (containsBinaryNull(bytes)) {
            throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是受支持的 UTF-8 文本");
        }
        boolean bom = hasBom(bytes);
        int offset = bom ? 3 : 0;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return new Utf8TextDocument(decoded.toString(), bytes, bom);
        } catch (CharacterCodingException exception) {
            throw error(ToolErrorCode.UNSUPPORTED_ENCODING, "目标不是有效 UTF-8 文本");
        }
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    private static boolean containsBinaryNull(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static WorkspaceAccessException error(ToolErrorCode code, String message) {
        return new WorkspaceAccessException(ToolError.of(code, message));
    }
}
