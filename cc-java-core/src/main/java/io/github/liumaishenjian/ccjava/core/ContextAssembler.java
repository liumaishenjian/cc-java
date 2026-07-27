package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import java.util.List;

/**
 * 把 Session 的规范历史编译为单个模型回合请求。
 *
 * <p>S01 只进行追加式 Context 组装，不做 Token 估算、淘汰、摘要或压缩。
 * 这些策略在 S07 进入独立 Context Manager。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ContextAssembler {

    /**
     * 创建一个不会随 Session 后续变化的 Model Request。
     *
     * @param session         当前 Session
     * @param runId           当前 Run
     * @param turnNumber      从 1 开始的模型回合序号
     * @param toolDefinitions 当前可见 Tool 定义
     * @return 不可变请求快照
     */
    ModelRequest assemble(
            AgentSession session,
            RunId runId,
            int turnNumber,
            List<ToolDefinition> toolDefinitions);
}
