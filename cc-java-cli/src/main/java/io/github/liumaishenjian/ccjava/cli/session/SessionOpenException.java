package io.github.liumaishenjian.ccjava.cli.session;

/**
 * 持久 Session 无法安全打开时的类型化架构边缘异常。
 *
 * @since 0.6.0
 */
public final class SessionOpenException extends RuntimeException {

    /** 不包含路径、内容或底层异常原文的稳定错误码。 */
    private final String code;

    /**
     * 创建不暴露绝对路径或 journal 内容的失败。
     *
     * @param code 稳定错误码
     * @param message 安全说明
     */
    public SessionOpenException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回可供 Surface 分类的稳定错误码。
     *
     * @return 稳定错误码
     */
    public String code() {
        return code;
    }
}
