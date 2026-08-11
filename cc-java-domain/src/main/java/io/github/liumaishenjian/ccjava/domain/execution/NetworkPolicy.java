package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.List;
import java.util.Objects;

/**
 * 后端所拥有进程的网络策略；不适用于当前 JVM 内 HTTP。
 *
 * @param denyAll 是否拒绝全部网络
 * @param allowedEndpoints denyAll=false 时仍需 OS 强制的精确端点
 * @since 0.13.0
 */
public record NetworkPolicy(boolean denyAll, List<String> allowedEndpoints) {
    /** 冻结允许端点，防止执行期间由调用方扩大网络范围。 */
    public NetworkPolicy {
        allowedEndpoints = List.copyOf(Objects.requireNonNull(allowedEndpoints));
    }

    /**
     * 创建不允许任何网络端点的策略。
     *
     * @return 全网络拒绝策略
     */
    public static NetworkPolicy denyAllNetwork() {
        return new NetworkPolicy(true, List.of());
    }
}
