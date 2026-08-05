package io.github.liumaishenjian.ccjava.core.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存完整 EffectiveSettings 的 last-known-good 发布槽。
 *
 * <p>本类型不读取来源、不合并字段也不重试刷新。调用方必须先在私有候选中完成全部读取、
 * 校验和合并，再以观察到的 revision 调用 compare-and-set 发布。读者只能观察到空、前一完整
 * 快照或后一完整快照，不能观察到来源或字段的中间状态。</p>
 *
 * @since 0.8.0
 */
public class SettingsSnapshotStore {
    private final AtomicReference<EffectiveSettingsSnapshot> current = new AtomicReference<>();

    /** 创建尚未建立 last-known-good 的发布槽。 */
    public SettingsSnapshotStore() {
    }

    // 同包测试接缝，用于验证版本耗尽；生产从空槽开始。
    SettingsSnapshotStore(EffectiveSettingsSnapshot initial) {
        current.set(Objects.requireNonNull(initial, "initial 不能为空"));
    }

    /**
     * 返回当前完整 last-known-good 快照。
     *
     * @return 尚未有完整成功刷新时为空
     */
    public Optional<EffectiveSettingsSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    /**
     * 仅当当前发布版本仍等于预期版本时原子替换快照。
     *
     * <p>首次发布使用空预期；已存在快照时 replacement 必须使用严格更大的版本。失败表示
     * 其他刷新已经获胜，调用方必须丢弃自己的候选而非以旧输入重试覆盖。</p>
     *
     * @param expectedRevision 刷新开始时观察到的版本；尚无快照时为空
     * @param replacement 已完整构建的候选快照
     * @return 成功发布时为 {@code true}
     */
    public boolean replaceIfCurrent(Optional<Long> expectedRevision, EffectiveSettingsSnapshot replacement) {
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision 不能为空");
        replacement = Objects.requireNonNull(replacement, "replacement 不能为空");
        EffectiveSettingsSnapshot observed = current.get();
        if (!matches(observed, expectedRevision) || observed != null && replacement.revision() <= observed.revision()) {
            return false;
        }
        return current.compareAndSet(observed, replacement);
    }

    private static boolean matches(EffectiveSettingsSnapshot observed, Optional<Long> expectedRevision) {
        return observed == null ? expectedRevision.isEmpty()
                : expectedRevision.isPresent() && observed.revision() == expectedRevision.get();
    }
}
