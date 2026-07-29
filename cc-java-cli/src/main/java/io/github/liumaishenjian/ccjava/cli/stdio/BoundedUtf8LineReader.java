package io.github.liumaishenjian.ccjava.cli.stdio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 从字节流读取有界 UTF-8 行，避免 {@code BufferedReader.readLine()} 在换行前无限分配。
 */
final class BoundedUtf8LineReader {

    private final InputStream input;
    private final int maxLineBytes;

    BoundedUtf8LineReader(InputStream input, int maxLineBytes) {
        this.input = Objects.requireNonNull(input, "input 不能为空");
        if (maxLineBytes < 1) {
            throw new IllegalArgumentException("maxLineBytes 必须大于 0");
        }
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * 读取下一行。超过限制时会消费到当前行末，再报告错误，保证下一条消息仍可读取。
     *
     * @return 不包含 CR/LF 的文本；干净 EOF 返回 {@code null}
     * @throws IOException 读取失败时
     * @throws StdioProtocolException 行过大或不是合法 UTF-8 时
     */
    String readLine() throws IOException, StdioProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                Math.min(maxLineBytes, 1024));
        while (true) {
            int current = input.read();
            if (current == -1) {
                return buffer.size() == 0 ? null : decode(buffer.toByteArray());
            }
            if (current == '\n') {
                return decode(stripTrailingCarriageReturn(buffer.toByteArray()));
            }
            if (buffer.size() == maxLineBytes) {
                discardCurrentLine();
                throw new StdioProtocolException(
                        "LINE_TOO_LARGE",
                        StdioProtocol.UNAVAILABLE_REQUEST_ID,
                        "输入行超过字节限制");
            }
            buffer.write(current);
        }
    }

    private void discardCurrentLine() throws IOException {
        int current;
        do {
            current = input.read();
        } while (current != -1 && current != '\n');
    }

    private byte[] stripTrailingCarriageReturn(byte[] bytes) {
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
            return bytes;
        }
        byte[] result = new byte[bytes.length - 1];
        System.arraycopy(bytes, 0, result, 0, result.length);
        return result;
    }

    private String decode(byte[] bytes) throws StdioProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new StdioProtocolException(
                    "INVALID_UTF8",
                    StdioProtocol.UNAVAILABLE_REQUEST_ID,
                    "输入行不是合法 UTF-8");
        }
    }
}
