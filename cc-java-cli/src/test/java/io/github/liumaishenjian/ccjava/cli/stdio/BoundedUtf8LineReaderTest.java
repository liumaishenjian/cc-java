package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedUtf8LineReaderTest {

    @Test
    void readsChineseCrLfAndCleanEof() throws Exception {
        BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new ByteArrayInputStream("你好\r\n".getBytes(StandardCharsets.UTF_8)),
                32);

        assertThat(reader.readLine()).isEqualTo("你好");
        assertThat(reader.readLine()).isNull();
    }

    @Test
    void rejectsInvalidUtf8() {
        BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new ByteArrayInputStream(new byte[]{(byte) 0xC3, 0x28, '\n'}),
                32);

        assertThatThrownBy(reader::readLine)
                .isInstanceOf(StdioProtocolException.class)
                .extracting(exception -> ((StdioProtocolException) exception).code())
                .isEqualTo("INVALID_UTF8");
    }

    @Test
    void discardsOversizedLineAndCanReadNextMessage() throws Exception {
        BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new ByteArrayInputStream("12345\nok\n".getBytes(StandardCharsets.UTF_8)),
                4);

        assertThatThrownBy(reader::readLine)
                .isInstanceOf(StdioProtocolException.class)
                .extracting(exception -> ((StdioProtocolException) exception).code())
                .isEqualTo("LINE_TOO_LARGE");
        assertThat(reader.readLine()).isEqualTo("ok");
    }
}
