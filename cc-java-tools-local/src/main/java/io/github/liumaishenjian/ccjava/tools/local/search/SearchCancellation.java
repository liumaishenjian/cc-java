package io.github.liumaishenjian.ccjava.tools.local.search;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 精确文本搜索进程可观察的最小取消端口。
 *
 * <p>该端口不依赖 Core 的取消类型，避免本地 Tool 适配器反向绑定 Runtime 实现。
 * 组合根负责把一次 Agent Run 的取消状态映射为本端口；搜索进程只轮询状态并负责
 * 终止自己启动的进程树。</p>
 *
 * @since 0.3.1
 */
@FunctionalInterface
public interface SearchCancellation {

    /**
     * 判断调用者是否已经请求取消当前搜索。
     *
     * @return 已请求取消时为 {@code true}
     */
    boolean isCancellationRequested();

    /**
     * 返回永不取消的默认实现，供尚未接入 Runtime Cancellation 的调用路径使用。
     *
     * @return 永不取消的取消端口
     */
    static SearchCancellation none() {
        return () -> false;
    }

    /**
     * 从布尔状态提供者创建取消端口。
     *
     * @param supplier 每次轮询时读取的取消状态
     * @return 取消端口
     */
    static SearchCancellation from(BooleanSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier 不能为空");
        return supplier::getAsBoolean;
    }
}
