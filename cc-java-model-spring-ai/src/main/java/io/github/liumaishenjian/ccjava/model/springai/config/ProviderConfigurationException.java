package io.github.liumaishenjian.ccjava.model.springai.config;

/**
 * 表示本地 Provider 配置缺失、格式错误或无法安全读取。
 *
 * <p>异常只暴露稳定错误码和不含配置值的诊断文本，防止 API Key
 * 经由普通异常日志泄漏。</p>
 *
 * @since 0.1.0
 */
public final class ProviderConfigurationException extends RuntimeException {

    /** 不包含配置值的稳定错误分类。 */
    private final Code code;

    /**
     * 创建不携带底层敏感内容的配置异常。
     *
     * @param code 稳定错误分类
     * @param message 不包含任何配置值的诊断信息
     */
    public ProviderConfigurationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回可用于 CLI 诊断和测试断言的稳定错误码。
     *
     * @return 错误分类
     */
    public Code code() {
        return code;
    }

    /**
     * Provider 配置加载阶段的稳定错误分类。
     *
     * @since 0.1.0
     */
    public enum Code {
        /** 本地配置超过 Loader 允许的最大字节数。 */
        FILE_TOO_LARGE,
        /** 固定配置路径指向符号链接，存在读取边界逃逸风险。 */
        SYMBOLIC_LINK_REJECTED,
        /** 固定配置文件存在但无法作为普通 UTF-8 文件读取。 */
        READ_FAILED,
        /** 文件与环境覆盖层均没有提供必填配置。 */
        REQUIRED_VALUE_MISSING,
        /** Base URL 不是受支持且不携带凭证的绝对 HTTP(S) 地址。 */
        INVALID_BASE_URL,
        /** 模型名包含控制字符或超过长度限制。 */
        INVALID_MODEL
    }
}
