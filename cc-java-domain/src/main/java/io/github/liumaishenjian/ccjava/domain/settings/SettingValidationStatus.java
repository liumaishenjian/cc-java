package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * 单个 Settings provenance 所关联来源的完整校验结论。
 *
 * <p>该枚举只描述候选来源的结构、类型及受信注册表校验是否完成；它不替代 S05 权限决策，
 * 也不携带失败正文或底层异常。</p>
 *
 * @since 0.8.0
 */
public enum SettingValidationStatus {
    /** 来源完整通过解析、schema 和受信注册表校验。 */
    VALID,
    /** 来源未通过完整校验，因此不得参与最终 Settings 合并。 */
    INVALID
}
