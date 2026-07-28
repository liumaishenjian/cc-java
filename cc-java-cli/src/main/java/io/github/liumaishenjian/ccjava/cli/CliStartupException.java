package io.github.liumaishenjian.ccjava.cli;

import java.util.Objects;

/**
 * 表示 CLI Composition Root 无法创建 Provider、Runtime 或终端资源。
 *
 * <p>消息必须经过适配器脱敏；命令入口不会打印底层异常或堆栈。</p>
 *
 * @since 0.1.0
 */
public final class CliStartupException extends Exception {

    /** 调用方可安全映射到进程状态的退出分类。 */
    private final CliExitCode exitCode;

    /**
     * 创建安全启动错误。
     *
     * @param exitCode 进程退出分类
     * @param message  不含 Secret 的诊断
     */
    public CliStartupException(CliExitCode exitCode, String message) {
        super(Objects.requireNonNull(message, "message 不能为空"));
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode 不能为空");
    }

    /**
     * 返回应交给进程的退出分类。
     *
     * @return CLI 退出码
     */
    public CliExitCode exitCode() {
        return exitCode;
    }
}
