package io.github.liumaishenjian.ccjava.core.plugin;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独占一组 Plugin AgentTool、底层资源及其 snapshot lease。
 *
 * <p>关闭顺序为底层资源逆序，最后释放 snapshot lease；每个资源和 lease 最多关闭一次。
 * Tool 本身如需关闭，必须作为资源按创建顺序加入。关闭会尝试全部资源，并把后续失败作为
 * suppressed exception 保留，不输出资源文本。</p>
 *
 * @since 0.11.0
 */
public final class PluginToolContribution implements AutoCloseable {

    private final List<AgentTool> tools;
    private final List<AutoCloseable> resources;
    private final PluginLease snapshotLease;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 接管 Tool、底层资源与 snapshot lease 的独占所有权。
     *
     * @param tools 要注册到唯一 Pipeline 的 Tool
     * @param resources 按创建顺序排列的底层资源
     * @param snapshotLease contribution 固定的 generation lease
     */
    public PluginToolContribution(
            List<? extends AgentTool> tools,
            List<? extends AutoCloseable> resources,
            PluginLease snapshotLease) {
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools 不能为空"));
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources 不能为空"));
        this.snapshotLease = Objects.requireNonNull(snapshotLease, "snapshotLease 不能为空");
        if (this.tools.stream().anyMatch(Objects::isNull)
                || this.resources.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("Tool 或 resource 不能为空");
        }
    }

    /**
     * 返回按稳定注册顺序排列的不可变 Tool 列表。
     *
     * @return contribution 独占的 Tool
     */
    public List<AgentTool> tools() {
        return tools;
    }

    /**
     * 返回 contribution 固定的 Plugin snapshot。
     *
     * @return snapshot lease 绑定的精确 generation
     */
    public io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot snapshot() {
        return snapshotLease.snapshot();
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var closeables = new ArrayList<AutoCloseable>(resources);
        Exception failure = null;
        for (int index = closeables.size() - 1; index >= 0; index--) {
            failure = close(closeables.get(index), failure);
        }
        failure = close(snapshotLease, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception close(AutoCloseable closeable, Exception failure) {
        try {
            closeable.close();
        } catch (Exception exception) {
            if (failure == null) {
                failure = new Exception("Plugin contribution 关闭失败");
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }
}
