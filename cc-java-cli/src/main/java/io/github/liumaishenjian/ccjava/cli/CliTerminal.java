package io.github.liumaishenjian.ccjava.cli;

import java.io.PrintWriter;

/**
 * 抽象 Interactive Session 需要的最小终端能力。
 *
 * <p>JLine 类型只存在于具体适配器。Fake Terminal 可以脚本化输入、中断和 EOF，
 * 从而让普通 CI 不依赖真实 TTY。</p>
 *
 * @since 0.1.0
 */
public interface CliTerminal extends AutoCloseable {

    /**
     * 返回终端是否具备交互输入能力。
     *
     * @return 可安全进入 REPL 时为 {@code true}
     */
    boolean interactive();

    /**
     * 返回当前终端是否允许 ANSI 样式。
     *
     * @return 支持且未被用户禁用时为 {@code true}
     */
    boolean ansiSupported();

    /**
     * 读取一条经过行编辑的用户输入。
     *
     * @param prompt 提示符
     * @return 用户输入
     * @throws UserInterruptException idle 状态下用户按下 Ctrl+C
     * @throws EndOfInputException 输入到达 EOF
     */
    String readLine(String prompt)
            throws UserInterruptException, EndOfInputException;

    /**
     * 在当前模型 Run 期间临时安装 Ctrl+C 回调。
     *
     * <p>关闭返回句柄时必须恢复先前处理器。回调只应触发
     * {@link io.github.liumaishenjian.ccjava.core.CancellationSource#cancel()}，
     * 不得渲染终端。</p>
     *
     * @param handler 中断回调
     * @return 恢复先前处理器的句柄
     */
    InterruptRegistration onInterrupt(Runnable handler);

    /**
     * 返回人类输出 Writer。
     *
     * @return 终端 Writer
     */
    PrintWriter writer();

    /**
     * 关闭并恢复终端资源。
     */
    @Override
    void close();

    /**
     * 表示 idle 输入被用户中断。
     */
    final class UserInterruptException extends Exception {

        /** 创建输入中断信号。 */
        public UserInterruptException() {
            super("用户中断当前输入");
        }
    }

    /**
     * 表示输入流已关闭。
     */
    final class EndOfInputException extends Exception {

        /** 创建 EOF 信号。 */
        public EndOfInputException() {
            super("终端输入已结束");
        }
    }

    /**
     * 表示需要恢复的临时信号处理器。
     */
    @FunctionalInterface
    interface InterruptRegistration extends AutoCloseable {

        /** 恢复先前处理器。 */
        @Override
        void close();
    }
}
