package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import java.util.Objects;

/**
 * 在单个进程内 Session 中驱动基础 JLine REPL。
 *
 * <p>S02 只处理普通输入、idle Ctrl+C、活动 Run 取消、EOF 与 {@code /exit}。
 * 持久历史、多行解析、补全、完整 Slash Command 和 Steering 延后到 S08。</p>
 *
 * @since 0.1.0
 */
public final class InteractiveSession {

    private static final String PROMPT = "cc-java> ";

    private final CliRuntime runtime;
    private final CliTerminal terminal;
    private final TerminalRenderer renderer;

    /**
     * 创建交互 Session。
     *
     * @param runtime  连续的内存 Runtime Session
     * @param terminal Interactive Terminal
     * @param renderer 事件渲染器
     */
    public InteractiveSession(
            CliRuntime runtime,
            CliTerminal terminal,
            TerminalRenderer renderer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.terminal = Objects.requireNonNull(terminal, "terminal 不能为空");
        this.renderer = Objects.requireNonNull(renderer, "renderer 不能为空");
    }

    /**
     * 运行 REPL，直到 {@code /exit} 或 EOF。
     *
     * @return 正常结束或不可恢复 CLI 错误的退出码
     */
    public int run() {
        while (true) {
            String line;
            try {
                line = terminal.readLine(PROMPT);
            } catch (CliTerminal.UserInterruptException exception) {
                // idle Ctrl+C 只清除当前输入，不结束 Session。
                continue;
            } catch (CliTerminal.EndOfInputException exception) {
                return CliExitCode.SUCCESS.code();
            } catch (RuntimeException exception) {
                renderer.error("读取终端输入失败");
                return CliExitCode.INTERNAL_ERROR.code();
            }

            if (line == null || line.isBlank()) {
                continue;
            }
            if ("/exit".equals(line.trim())) {
                return CliExitCode.SUCCESS.code();
            }

            CancellationSource cancellation = new CancellationSource();
            renderer.beginRun();
            AgentRunResult result;
            try (CliTerminal.InterruptRegistration ignored =
                    terminal.onInterrupt(cancellation::cancel)) {
                result = runtime.run(line, cancellation.token());
            } catch (RuntimeException exception) {
                renderer.error("Runtime 执行失败");
                return CliExitCode.INTERNAL_ERROR.code();
            }
            renderer.completeRun(result);
            // USER_CANCELLED 和其他单 Run 终态都不会隐式关闭 Session。
        }
    }
}
