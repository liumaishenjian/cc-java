package io.github.liumaishenjian.ccjava.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class JLineCliTerminalTest {

    @Test
    void readsFromVirtualDumbTerminalAndReportsNoAnsi() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(
                        new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)),
                        output)
                .type("dumb")
                .build();
        JLineCliTerminal cliTerminal = new JLineCliTerminal(terminal, false);
        try {
            assertThat(cliTerminal.readLine("prompt> ")).isEqualTo("hello");
            assertThat(cliTerminal.ansiSupported()).isFalse();
            assertThatThrownBy(() -> cliTerminal.readLine("prompt> "))
                    .isInstanceOf(CliTerminal.EndOfInputException.class);
        } finally {
            cliTerminal.close();
        }
    }

    @Test
    void temporaryRunInterruptHandlerIsRestoredAfterRegistrationCloses()
            throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream())
                .type("dumb")
                .build();
        AtomicInteger previousHandler = new AtomicInteger();
        AtomicInteger runHandler = new AtomicInteger();
        terminal.handle(Terminal.Signal.INT, ignored -> previousHandler.incrementAndGet());
        JLineCliTerminal cliTerminal = new JLineCliTerminal(terminal, true);
        try {
            CliTerminal.InterruptRegistration registration =
                    cliTerminal.onInterrupt(runHandler::incrementAndGet);
            terminal.raise(Terminal.Signal.INT);
            registration.close();
            terminal.raise(Terminal.Signal.INT);

            assertThat(runHandler).hasValue(1);
            assertThat(previousHandler).hasValue(1);
            assertThat(cliTerminal.ansiSupported()).isFalse();
        } finally {
            cliTerminal.close();
        }
    }
}
