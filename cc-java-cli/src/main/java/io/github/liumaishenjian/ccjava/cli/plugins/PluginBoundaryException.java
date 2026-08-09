package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import java.util.Objects;

/** Plugin 文件系统/JSON 边界的结构化隐私安全失败。 */
public final class PluginBoundaryException extends RuntimeException {
    private final PluginErrorCode code;

    public PluginBoundaryException(PluginErrorCode code) {
        super(Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code;
    }

    public PluginErrorCode code() {
        return code;
    }
}
