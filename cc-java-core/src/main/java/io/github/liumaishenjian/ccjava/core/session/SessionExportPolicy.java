package io.github.liumaishenjian.ccjava.core.session;

/**
 * Session Export 的明确隐私策略。
 *
 * @param includeContent 是否包含正文
 * @param redactContent 是否执行脱敏
 * @param explicitlyConfirmed 是否针对本次导出确认
 * @since 0.1.0
 */
public record SessionExportPolicy(boolean includeContent, boolean redactContent, boolean explicitlyConfirmed) {
    /** 正文导出必须同时启用脱敏并针对本次操作显式确认。 */
    public SessionExportPolicy { if (includeContent && (!redactContent || !explicitlyConfirmed)) throw new IllegalArgumentException("正文导出需要脱敏与显式确认"); }
    /**
     * 返回无需确认且不包含正文的默认策略。
     *
     * @return metadata-only 导出策略
     */
    public static SessionExportPolicy metadataOnly() { return new SessionExportPolicy(false, true, false); }
}
