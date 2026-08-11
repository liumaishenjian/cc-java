package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.List;
import java.util.Objects;

/**
 * 以 canonical identity 字符串表达的文件隔离策略；Path 转换只允许发生在边缘适配器。
 *
 * @param readOnlyRoots 可读但不可写根
 * @param writableRoots 显式可写根
 * @param deniedRoots 无条件保护的 carve-out
 * @since 0.13.0
 */
public record FileAccessPolicy(
        List<String> readOnlyRoots,
        List<String> writableRoots,
        List<String> deniedRoots) {
    /** 冻结只读/可写 roots 与控制面保护集合。 */
    public FileAccessPolicy {
        readOnlyRoots = copy(readOnlyRoots);
        writableRoots = copy(writableRoots);
        deniedRoots = copy(deniedRoots);
    }

    private static List<String> copy(List<String> values) {
        return List.copyOf(Objects.requireNonNull(values, "roots 不能为空"));
    }
}
