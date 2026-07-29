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

/** 以严格 UTF-8 和字节 ceiling 读取普通文本。 */
final class Utf8TextReader {

    private Utf8TextReader() {
    }

    static String read(Path path, long maximumBytes) throws WorkspaceAccessException {
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
        int offset = hasBom(bytes) ? 3 : 0;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
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
