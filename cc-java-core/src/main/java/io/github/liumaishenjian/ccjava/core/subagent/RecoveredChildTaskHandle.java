package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import java.time.Duration;
import java.util.Objects;

/**
 * 把 no-replay 恢复结果投影为只读终态 Handle。
 *
 * <p>该 Handle 永不提交模型、Tool 或 Git 操作；inspect/await 返回同一 durable 投影，cancel 恒为
 * {@code false}，从而让恢复任务进入普通 registry 查询路径而不伪装成可继续执行的后台任务。</p>
 *
 * @since 0.12.0
 */
public final class RecoveredChildTaskHandle implements ChildTaskHandle {
    private final ChildTaskReport report;

    public RecoveredChildTaskHandle(ChildTaskReport report) {
        this.report = Objects.requireNonNull(report, "report 不能为空");
        if (!report.status().terminal()) throw new IllegalArgumentException("恢复 Handle 必须是终态");
    }

    @Override public ChildTaskId id() { return report.taskId(); }
    @Override public ChildTaskReport inspect() { return report; }
    @Override public ChildTaskReport await(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        return report;
    }
    @Override public boolean cancel() { return false; }
}
