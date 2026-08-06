package io.github.liumaishenjian.ccjava.cli.runtime;

/**
 * Surface 进程内、可丢弃状态的清理端口。
 *
 * <p>实现只能清理输入缓冲、展示历史或未发送 steering；不得访问 Canonical Transcript、
 * Session JSONL、Tool、Settings 或活动 Run。</p>
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface SurfaceTransientState {
    /** 清理当前 Surface 的 transient state。 */
    void clear();
}
