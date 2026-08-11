package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import java.time.Duration;
import java.util.Optional;

/**
 * 前台/后台共用的子任务观察与取消句柄。
 *
 * @since 0.12.0
 */
public interface ChildTaskHandle {
    /**
     * 查询子任务稳定 identity。
     *
     * @return task ID
     */
    ChildTaskId id();

    /**
     * 不阻塞地检查当前状态。
     *
     * @return 隐私安全状态投影
     */
    ChildTaskReport inspect();

    /**
     * 有界等待任务状态变化或终态。
     *
     * @param timeout 最大等待时间
     * @return 等待结束时的状态投影
     * @throws InterruptedException 调用线程被中断时
     */
    ChildTaskReport await(Duration timeout) throws InterruptedException;

    /**
     * 请求取消当前任务。
     *
     * @return 是否首次接受取消请求
     */
    boolean cancel();

    /**
     * 显式保留任务拥有的 worktree。
     *
     * @return 固定处置状态；无 worktree 或尚未终态时为空
     */
    default Optional<String> keepWorktree() {
        return Optional.empty();
    }

    /**
     * 显式删除可证明 clean 的任务 worktree。
     *
     * @return 固定处置状态；无法证明时返回保留终态
     */
    default Optional<String> removeWorktree() {
        return Optional.empty();
    }
}
