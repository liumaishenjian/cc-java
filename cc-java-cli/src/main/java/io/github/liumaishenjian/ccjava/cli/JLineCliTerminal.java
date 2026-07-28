package io.github.liumaishenjian.ccjava.cli;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;

/**
 * 使用 JLine 提供行编辑、EOF 与 Ctrl+C 的 Interactive Terminal。
 *
 * <p>Runtime 执行期间临时替换 {@link Terminal.Signal#INT} 处理器；结束后立即恢复。
 * 这样 idle {@link LineReader} 仍用 {@code UserInterruptException} 处理 Ctrl+C，
 * 而活动 Run 只接收取消信号，不直接退出 Session。</p>
 *
 * @since 0.1.0
 */
public final class JLineCliTerminal implements CliTerminal {

    private final Terminal terminal;
    private final LineReader lineReader;
    private final boolean interactive;
    private final boolean ansiSupported;

    /**
     * 包装一个已经创建的 JLine Terminal。
     *
     * @param terminal JLine Terminal
     * @param noColor  是否强制关闭 ANSI
     */
    public JLineCliTerminal(Terminal terminal, boolean noColor) {
        this.terminal = Objects.requireNonNull(terminal, "terminal 不能为空");
        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        String type = terminal.getType() == null
                ? ""
                : terminal.getType().toLowerCase(Locale.ROOT);
        this.interactive = !type.startsWith(Terminal.TYPE_DUMB);
        this.ansiSupported = !noColor
                && interactive;
    }

    @Override
    public boolean interactive() {
        return interactive;
    }

    @Override
    public boolean ansiSupported() {
        return ansiSupported;
    }

    @Override
    public String readLine(String prompt)
            throws CliTerminal.UserInterruptException,
            CliTerminal.EndOfInputException {
        try {
            return lineReader.readLine(prompt);
        } catch (org.jline.reader.UserInterruptException exception) {
            throw new CliTerminal.UserInterruptException();
        } catch (EndOfFileException exception) {
            throw new CliTerminal.EndOfInputException();
        }
    }

    @Override
    public InterruptRegistration onInterrupt(Runnable handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        Terminal.SignalHandler previous = terminal.handle(
                Terminal.Signal.INT,
                ignored -> handler.run());
        return () -> terminal.handle(Terminal.Signal.INT, previous);
    }

    @Override
    public PrintWriter writer() {
        return terminal.writer();
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (Exception ignored) {
            // 终端已经结束；关闭失败不能覆盖 Runtime 的确定性终态。
        }
    }
}
