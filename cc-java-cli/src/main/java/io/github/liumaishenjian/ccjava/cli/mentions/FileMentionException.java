package io.github.liumaishenjian.ccjava.cli.mentions;

/**
 * 显式文件提及在进入 Agent Runtime 前失败的隐私安全异常。
 *
 * <p>异常只暴露固定 code，不保存绝对路径、文件正文或底层异常文本。</p>
 *
 * @since 0.8.1
 */
public final class FileMentionException extends RuntimeException {

    /** 所有无效显式提及共用的安全诊断码。 */
    public static final String CODE = "FILE_MENTION_INVALID";

    /** 创建固定诊断。 */
    public FileMentionException() {
        super(CODE);
    }

    /**
     * 返回可安全投影到协议或日志的固定诊断码。
     *
     * @return 始终为 {@link #CODE}
     */
    public String code() {
        return CODE;
    }
}
