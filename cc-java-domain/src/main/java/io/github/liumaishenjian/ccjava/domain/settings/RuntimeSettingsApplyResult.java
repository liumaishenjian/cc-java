package io.github.liumaishenjian.ccjava.domain.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Runtime Settings 候选投影与原子发布的结果。
 *
 * <p>无论成功或失败，{@code configuration} 都是会话当前完整配置；失败时它必然是应用前的
 * 同一不可变值，调用者不能观察到字段级部分更新。</p>
 *
 * @param configuration 发布后或保留的完整配置
 * @param applied 是否完成替换
 * @param diagnostics 失败时的固定无敏感正文诊断
 * @since 0.8.0
 */
public record RuntimeSettingsApplyResult(RuntimeConfiguration configuration, boolean applied,
                                         List<RuntimeSettingsDiagnostic> diagnostics) {
    /** 冻结完整结果，并限制成功结果不携带拒绝诊断。 */
    public RuntimeSettingsApplyResult {
        configuration = Objects.requireNonNull(configuration, "configuration 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        if (applied && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("成功应用不能携带拒绝诊断");
        }
        if (!applied && diagnostics.isEmpty()) {
            throw new IllegalArgumentException("拒绝应用必须携带诊断");
        }
    }

    /**
     * 返回成功发布结果。
     *
     * @param configuration 已替换配置
     * @return 不带诊断的成功结果
     */
    public static RuntimeSettingsApplyResult applied(RuntimeConfiguration configuration) {
        return new RuntimeSettingsApplyResult(configuration, true, List.of());
    }

    /**
     * 返回保留旧值的拒绝结果。
     *
     * @param configuration 未被替换的旧配置
     * @param code 固定拒绝分类
     * @return 带单个安全诊断的失败结果
     */
    public static RuntimeSettingsApplyResult rejected(RuntimeConfiguration configuration,
                                                      RuntimeSettingsDiagnosticCode code) {
        return new RuntimeSettingsApplyResult(configuration, false,
                List.of(new RuntimeSettingsDiagnostic(code)));
    }

    /**
     * 返回第一个失败诊断。
     *
     * @return 成功时为空
     */
    public Optional<RuntimeSettingsDiagnostic> diagnostic() {
        return diagnostics.stream().findFirst();
    }
}
