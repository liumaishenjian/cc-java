package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import java.util.Objects;

/** Plugin 文件系统/JSON 边界的结构化隐私安全失败。 */
public final class PluginBoundaryException extends RuntimeException {
    /** 不含不可信文本的固定错误码。 */
    private final PluginErrorCode code;

    /**
     * 创建只暴露固定错误码、不携带路径或正文的边界异常。
     *
     * @param code Plugin 边界错误码
     */
    public PluginBoundaryException(PluginErrorCode code) {
        super(Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code;
    }

    /**
     * 返回稳定结构化错误码。
     *
     * @return 不含不可信输入的 Plugin 错误码
     */
    public PluginErrorCode code() {
        return code;
    }
}
