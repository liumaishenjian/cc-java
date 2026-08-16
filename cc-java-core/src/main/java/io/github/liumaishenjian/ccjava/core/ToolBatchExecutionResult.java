package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ToolResult;
import java.util.List;
import java.util.Objects;

/**
 * 一批模型 Tool Call 的有序结果及其后续 Run 控制信号。
 *
 * <p>results 与原模型调用保持同一顺序和一一对应；stopAfterBatch 只在所有结果已返回并由
 * AgentRuntime 追加到规范历史后生效，避免制造孤立的 Tool Call。</p>
 *
 * @param results 与模型原始调用顺序一致的完整 Tool Result
 * @param stopAfterBatch 所有结果完成配对后是否停止当前 Run
 * @since 0.15.0
 */
public record ToolBatchExecutionResult(List<ToolResult> results, boolean stopAfterBatch) {
    /** 复制结果集合，防止调用方在批次结算后改变协议顺序。 */
    public ToolBatchExecutionResult {
        results = List.copyOf(Objects.requireNonNull(results, "results 不能为空"));
    }
}
