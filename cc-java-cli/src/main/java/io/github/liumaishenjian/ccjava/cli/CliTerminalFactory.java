package io.github.liumaishenjian.ccjava.cli;

/**
 * 延迟创建 Interactive 模式使用的终端。
 *
 * <p>Print 模式不得调用该工厂，确保重定向输出时不初始化 JLine、不读取 stdin，
 * 也不输出终端探测告警。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface CliTerminalFactory {

    /**
     * 创建一个候选 Interactive Terminal。
     *
     * @param noColor 用户是否强制禁用 ANSI
     * @return 由调用者关闭的终端
     * @throws CliStartupException 终端无法初始化时
     */
    CliTerminal open(boolean noColor) throws CliStartupException;
}
