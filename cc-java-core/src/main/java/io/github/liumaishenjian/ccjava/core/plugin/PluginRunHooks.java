package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.core.hook.HookBinding;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把当前 Run 捕获的受信 Plugin/Skill Hook templates 转换为 S09 bindings 的宿主端口。
 *
 * <p>实现只能返回已经通过宿主 trust Gate 的绑定；不能执行 Tool、修改 Permission 或把 Hook
 * 注册为全局绑定。输入 fingerprint 来自当前 {@link PluginRunCoordinator} lease，而非磁盘实时扫描。</p>
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface PluginRunHooks {
    /**
     * 根据当前固定 generation 构造 Run-scoped bindings。
     *
     * @param runId bindings 所属 Run
     * @param fingerprints 当前 Run 捕获的 Plugin identity 与 tree digest
     * @return 已通过宿主 trust Gate 的 Run-scoped bindings
     */
    List<HookBinding> bindings(RunId runId, Map<PluginId, String> fingerprints);

    /**
     * 返回不贡献 Hook 的共享实现。
     *
     * @return 始终返回空 bindings 的实现
     */
    static PluginRunHooks none() {
        return (runId, fingerprints) -> {
            Objects.requireNonNull(runId, "runId 不能为空");
            Objects.requireNonNull(fingerprints, "fingerprints 不能为空");
            return List.of();
        };
    }
}
