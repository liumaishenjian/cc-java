package io.github.liumaishenjian.ccjava.protocol;

/** 不包含输入正文的稳定协议错误。 */
public final class ProtocolCodecException extends Exception {
    /** 不包含输入正文的 stable protocol 错误码。 */
    private final String code;
    /**
     * 创建仅携带固定错误码的协议拒绝。
     *
     * @param code stable protocol 固定错误码
     */
    public ProtocolCodecException(String code) { super("stable protocol message rejected"); this.code = code; }

    /**
     * 返回不包含输入正文的错误码。
     *
     * @return stable protocol 错误码
     */
    public String code() { return code; }
}
