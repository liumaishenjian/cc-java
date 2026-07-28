package io.github.liumaishenjian.ccjava.cli;

/**
 * 表示可安全展示给用户的 CLI 配置错误。
 *
 * <p>消息只能包含参数名、环境变量名或校验原因，不能包含 API Key。</p>
 *
 * @since 0.1.0
 */
public final class CliConfigurationException extends Exception {

    /**
     * 创建安全配置错误。
     *
     * @param message 不含 Secret 的说明
     */
    public CliConfigurationException(String message) {
        super(message);
    }
}
