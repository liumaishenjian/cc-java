package io.github.liumaishenjian.ccjava.domain.subagent;

/**
 * 子任务的不透明进程内身份。
 *
 * @param value 不包含路径或用户正文的稳定标识
 * @since 0.12.0
 */
public record ChildTaskId(String value) {
    public ChildTaskId {
        if (value == null || !value.matches("task-[a-zA-Z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("Child task ID 格式无效");
        }
    }
}
