package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 仅保留最新一份 Context Usage View 的线程安全旁路 collector。
 *
 * <p>本类型不按 Run 或 turn 建立历史索引，不写入 Session/Journal。关闭会先阻止后续发布再清除当前值，
 * 因此晚到的 preparation/recovery 不能留下 stale View。</p>
 *
 * @since 0.7.0
 */
public final class LatestContextUsageCollector implements ContextUsageObserver, AutoCloseable {

    /** 创建未关闭且尚未接收任何 Usage View 的 collector。 */
    public LatestContextUsageCollector() {
    }

    private final AtomicReference<ContextUsageView> latest = new AtomicReference<>();
    private boolean closed;

    /**
     * 保留最新快照；关闭后丢弃晚到快照。
     *
     * @param view 已完成的隐私安全快照
     */
    @Override
    public synchronized void publish(ContextUsageView view) {
        Objects.requireNonNull(view, "view 不能为空");
        if (!closed) {
            latest.set(view);
        }
    }

    /**
     * 返回当前最新快照。
     *
     * @return 尚无发布或已经关闭时为空
     */
    public synchronized Optional<ContextUsageView> latest() {
        return closed ? Optional.empty() : Optional.ofNullable(latest.get());
    }

    /** 清空当前快照，并永久拒绝后续发布。 */
    @Override
    public synchronized void close() {
        closed = true;
        latest.set(null);
    }
}
