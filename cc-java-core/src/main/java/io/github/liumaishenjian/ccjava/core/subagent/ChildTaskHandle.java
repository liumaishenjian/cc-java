package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskId;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskReport;
import java.time.Duration;

/** 前台/后台共用的子任务观察与取消句柄。 @since 0.12.0 */
public interface ChildTaskHandle {
    ChildTaskId id();
    ChildTaskReport inspect();
    ChildTaskReport await(Duration timeout) throws InterruptedException;
    boolean cancel();

    /** 显式保留任务拥有的 worktree；无 worktree 或尚未终态时返回空。 */
    default java.util.Optional<String> keepWorktree() { return java.util.Optional.empty(); }

    /** 显式删除可证明 clean 的任务 worktree；无法证明时返回保留终态。 */
    default java.util.Optional<String> removeWorktree() { return java.util.Optional.empty(); }
}
