package io.github.liumaishenjian.ccjava.cli.daemon;

import io.github.liumaishenjian.ccjava.protocol.ProtocolCodecException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

/**
 * stable v1 NDJSON stdio 传输循环。
 *
 * <p>该类型只做有界 framing、输出排空与 EOF disconnect fence；消息语义全部交给
 * {@link StableProtocolHandler}。v0 server 保持原义并与本入口并存至少一个 release。</p>
 *
 * @since 0.1.0
 */
public final class StableProtocolStdioServer {
    private final InputStream input;
    private final OutputStream output;
    private final StableProtocolHandler handler;

    /**
     * 创建拥有给定流 framing 生命周期的 stdio Server。
     *
     * @param input NDJSON 输入流
     * @param output NDJSON 输出流
     * @param handler stable protocol 连接处理器
     */
    public StableProtocolStdioServer(
            InputStream input, OutputStream output, StableProtocolHandler handler) {
        this.input = new BufferedInputStream(Objects.requireNonNull(input, "input 不能为空"));
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.handler = Objects.requireNonNull(handler, "handler 不能为空");
    }

    /**
     * 持续处理到 EOF、shutdown 或协议错误；任何退出都会关闭连接并 fence 迟到事件。
     *
     * @return 固定传输退出原因
     */
    public ExitReason run() {
        Thread writer = Thread.startVirtualThread(this::writeLoop);
        try {
            while (true) {
                byte[] line = readLine();
                if (line == null) {
                    return ExitReason.EOF;
                }
                handler.receive(line);
            }
        } catch (ProtocolCodecException failure) {
            return ExitReason.PROTOCOL_ERROR;
        } catch (IOException failure) {
            return ExitReason.IO_ERROR;
        } finally {
            handler.close();
            writer.interrupt();
            try {
                writer.join(Duration.ofSeconds(2));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var message = handler.takeOutput(Duration.ofMillis(100));
                if (message.isEmpty()) {
                    continue;
                }
                output.write(message.orElseThrow());
                output.write('\n');
                output.flush();
            }
        } catch (IOException transportClosed) {
            handler.close();
        }
    }

    private byte[] readLine() throws IOException, ProtocolCodecException {
        byte[] buffer = new byte[io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec.MAX_LINE_BYTES];
        int size = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                return size == 0 ? null : java.util.Arrays.copyOf(buffer, size);
            }
            if (value == '\n') {
                if (size > 0 && buffer[size - 1] == '\r') size--;
                if (size == 0) throw new ProtocolCodecException("MESSAGE_SIZE");
                return java.util.Arrays.copyOf(buffer, size);
            }
            if (size == buffer.length) {
                throw new ProtocolCodecException("MESSAGE_SIZE");
            }
            buffer[size++] = (byte) value;
        }
    }

    /** Stable stdio 传输的固定退出原因。 */
    public enum ExitReason {
        /** 输入正常到达 EOF。 */
        EOF,
        /** 消息 framing、codec 或连接状态非法。 */
        PROTOCOL_ERROR,
        /** 输入或输出流发生 I/O 失败。 */
        IO_ERROR
    }
}
