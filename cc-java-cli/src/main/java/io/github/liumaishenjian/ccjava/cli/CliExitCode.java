package io.github.liumaishenjian.ccjava.cli;

/**
 * Java Headless 的稳定进程退出码。
 *
 * <p>数值遵循 Picocli 的成功、软件错误和用法错误约定；取消使用常见的
 * {@code 128 + SIGINT(2)}。S02 只承诺这些高层分类，不把 Provider 私有错误码
 * 暴露为进程契约。</p>
 *
 * @since 0.1.0
 */
final class CliExitCode {

    static final int SUCCESS = 0;
    static final int RUNTIME_FAILURE = 1;
    static final int USAGE_OR_CONFIGURATION = 2;
    static final int USER_CANCELLED = 130;

    private CliExitCode() {
    }
}
