package io.github.liumaishenjian.ccjava.domain.plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Session 启动时固定且不热切换的 Plugin 快照集合。 @param snapshots 稳定 Plugin ID 顺序 @since 0.11.0 */
public record PluginSnapshotSet(List<PluginSnapshot> snapshots) {
    /** 防御性复制并拒绝重复 Plugin ID。 */
    public PluginSnapshotSet {
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots 不能为空"));
        var ids = new HashSet<PluginId>();
        if (snapshots.stream().anyMatch(snapshot -> !ids.add(snapshot.manifest().id()))) {
            throw new IllegalArgumentException("Session snapshot 不能包含重复 Plugin ID");
        }
    }
}
