package io.github.liumaishenjian.ccjava.cli;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 创建由 JLine 自动探测的平台 System Terminal。
 *
 * <p>允许 JLine 回退为 dumb terminal，但命令入口会检查
 * {@link CliTerminal#interactive()} 并拒绝在非 TTY 上等待输入。</p>
 *
 * @since 0.1.0
 */
public final class JLineCliTerminalFactory implements CliTerminalFactory {

    /** 创建系统终端工厂。 */
    public JLineCliTerminalFactory() {
    }

    @Override
    public CliTerminal open(boolean noColor) throws CliStartupException {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .build();
            return new JLineCliTerminal(terminal, noColor);
        } catch (IOException | RuntimeException exception) {
            throw new CliStartupException(
                    CliExitCode.CONFIGURATION,
                    "无法初始化交互终端；请使用 --print 运行非交互任务");
        }
    }
}
