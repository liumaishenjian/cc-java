package io.github.liumaishenjian.ccjava.domain.plugin;

/** Plugin registry 的封闭生命周期状态。 @since 0.11.0 */
public enum PluginRegistryState {
    /** 可供新 Session 获取 lease。 */ ACTIVE,
    /** 拒绝新 lease，等待已有引用归零。 */ QUIESCING,
    /** 已移出 active namespace。 */ REMOVED,
    /** 删除失败且不得重新激活的隐私安全墓碑。 */ TOMBSTONED
}
