package io.github.liumaishenjian.ccjava.core.settings;

import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Settings 刷新的安全结果。
 *
 * <p>未发布结果可携带导致候选被丢弃的诊断，或表示 CAS 竞争失败；两种情况均保留
 * {@code currentSnapshot} 指向的 last-known-good，不提供部分合并投影。</p>
 *
 * @param published 是否已原子发布新快照
 * @param currentSnapshot 操作结束后可用的完整快照
 * @param diagnostics 无正文、无路径的候选失败诊断
 * @since 0.8.0
 */
public record SettingsRefreshResult(boolean published, Optional<EffectiveSettingsSnapshot> currentSnapshot,
                                    List<ConfigurationDiagnostic> diagnostics) {
    /** 冻结结果集合并禁止成功发布同时携带候选失败诊断。 */
    public SettingsRefreshResult {
        currentSnapshot = Objects.requireNonNull(currentSnapshot, "currentSnapshot 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        if (published && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("成功发布不能携带失败诊断");
        }
    }

    /** 创建新完整快照已经发布的结果。 */
    static SettingsRefreshResult published(EffectiveSettingsSnapshot snapshot) {
        return new SettingsRefreshResult(true, Optional.of(Objects.requireNonNull(snapshot, "snapshot 不能为空")), List.of());
    }

    /** 创建候选被丢弃、已发布快照保持不变的结果。 */
    static SettingsRefreshResult notPublished(Optional<EffectiveSettingsSnapshot> currentSnapshot,
                                              List<ConfigurationDiagnostic> diagnostics) {
        return new SettingsRefreshResult(false, currentSnapshot, diagnostics);
    }

    @Override
    public String toString() {
        return "SettingsRefreshResult[published=" + published + ", currentSnapshot="
                + currentSnapshot.map(snapshot -> "revision=" + snapshot.revision()).orElse("<empty>")
                + ", diagnostics=" + diagnostics + "]";
    }
}
