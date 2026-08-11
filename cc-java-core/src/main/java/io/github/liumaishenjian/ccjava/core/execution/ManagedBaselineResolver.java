package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.domain.execution.EnvironmentPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.FileAccessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ManagedSecurityBaseline;
import io.github.liumaishenjian.ccjava.domain.execution.NetworkPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ProcessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.SecretPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以 deny-only 或 required-isolation 规则将受管底线与宿主策略求交集。
 *
 * <p>首轮骨架只允许减少根、端点和环境并增强隔离；任何放宽尝试均失败关闭。
 * 当前类型尚未接入生产装配，CFG-07 保持 L0。</p>
 *
 * @since 0.13.0
 */
public final class ManagedBaselineResolver {
    /** 创建无状态的受管安全底线合并器。 */
    public ManagedBaselineResolver() { }

    /**
     * 将宿主策略收紧到受管底线，不允许扩大任何许可集合。
     *
     * @param host 宿主已解析策略
     * @param managed 受管安全底线
     * @return 两者求交后的有效策略
     */
    public ExecutionPolicy resolve(
            ExecutionPolicy host,
            ManagedSecurityBaseline managed) {
        Objects.requireNonNull(host);
        Objects.requireNonNull(managed);
        ExecutionPolicy required = managed.requiredPolicy();
        return new ExecutionPolicy(
                new FileAccessPolicy(
                        intersect(
                                host.file().readOnlyRoots(),
                                required.file().readOnlyRoots()),
                        intersect(
                                host.file().writableRoots(),
                                required.file().writableRoots()),
                        union(
                                host.file().deniedRoots(),
                                required.file().deniedRoots())),
                new ProcessPolicy(
                        host.process().allowDescendants()
                                && required.process().allowDescendants(),
                        false,
                        false),
                new NetworkPolicy(
                        host.network().denyAll() || required.network().denyAll(),
                        intersect(
                                host.network().allowedEndpoints(),
                                required.network().allowedEndpoints())),
                new EnvironmentPolicy(intersectMap(
                        host.environment().variables(),
                        required.environment().variables())),
                new SecretPolicy(new HashSet<>(union(
                        new ArrayList<>(host.secret().deniedNames()),
                        new ArrayList<>(required.secret().deniedNames())))),
                host.requireIsolation() || required.requireIsolation(),
                union(host.provenance(), List.of(managed.provenance())));
    }

    private static <T> List<T> intersect(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first);
        result.retainAll(second);
        return List.copyOf(result);
    }

    private static <T> List<T> union(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first);
        for (T value : second) {
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> intersectMap(
            Map<String, String> first,
            Map<String, String> second) {
        Map<String, String> result = new HashMap<>();
        first.forEach((key, value) -> {
            if (Objects.equals(value, second.get(key))) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }
}
