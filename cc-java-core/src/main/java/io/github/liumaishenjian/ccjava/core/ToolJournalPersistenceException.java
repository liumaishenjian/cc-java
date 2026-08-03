package io.github.liumaishenjian.ccjava.core;

/**
 * 表示 Tool 的 resolved、started 或 completed 权威记录未能可靠持久化。
 *
 * <p>该异常是 Runtime fence 信号，不能转换成普通 Tool Result。转换会让模型继续下一回合，
 * 而恢复日志中缺少与 Assistant Tool Call 对应的权威终结记录。</p>
 *
 * @since 0.6.0
 */
public final class ToolJournalPersistenceException extends RuntimeException {

    /**
     * 创建 Tool journal fence 异常。
     *
     * @param message 不包含 Tool 参数或正文的固定说明
     * @param cause 持久化失败原因
     */
    public ToolJournalPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
