package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.Objects;

/**
 * Runtime Settings 应用失败的隐私安全诊断。
 *
 * <p>诊断只记录固定分类，不保存模型名、规则 selector、Tool 配置、锚点、Provider 端点或
 * 凭证，因此可安全用于 Surface 的受限状态投影。</p>
 *
 * @param code 固定失败分类
 * @since 0.8.0
 */
public record RuntimeSettingsDiagnostic(RuntimeSettingsDiagnosticCode code) {
    /** 创建仅含固定分类的诊断。 */
    public RuntimeSettingsDiagnostic {
        code = Objects.requireNonNull(code, "code 不能为空");
    }
}
