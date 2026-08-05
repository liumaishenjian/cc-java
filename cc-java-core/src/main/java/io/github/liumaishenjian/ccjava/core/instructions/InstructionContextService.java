package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResult;

/**
 * 在模型请求边界投影当前已验证 Instructions，并接收成功 Tool 的后续激活通知。
 *
 * <p>实现只能构造短生命周期 {@link ModelRequest}，不得修改 Canonical Transcript、
 * Session journal、权限、Tool registry 或 Checkpoint。成功 Tool 通知发生在 durable completion
 * 后；实现中的刷新失败必须保持最后一份完整投影，不能改变已完成 Tool 的结果。</p>
 *
 * @since 0.8.0
 */
public interface InstructionContextService {

    /**
     * 将当前完整 Instructions 快照投影到本回合请求。
     *
     * @param canonical 已由 ContextAssembler 生成的规范请求
     * @param cancellationToken 当前回合取消信号
     * @return 仅当前回合使用的请求；无法安全刷新时可返回最后完整投影
     */
    ModelRequest project(ModelRequest canonical, CancellationToken cancellationToken);

    /**
     * 接收已经成功且 durable completion 已完成的 Tool 调用。
     *
     * @param call 模型原始调用；实现不得从 Tool 输出文本推导路径
     * @param result 成功的标准化结果
     * @param cancellationToken 当前 Run 取消信号
     */
    void recordSuccessfulTool(ToolCall call, ToolResult result, CancellationToken cancellationToken);

    /**
     * 返回不改变请求且忽略成功 Tool 的兼容实现。
     *
     * @return 无副作用服务
     */
    static InstructionContextService noop() {
        return NoopInstructionContextService.INSTANCE;
    }

    /** 无状态兼容实现。 */
    enum NoopInstructionContextService implements InstructionContextService {
        /** 共享实例。 */
        INSTANCE;

        @Override
        public ModelRequest project(ModelRequest canonical, CancellationToken cancellationToken) {
            return canonical;
        }

        @Override
        public void recordSuccessfulTool(
                ToolCall call, ToolResult result, CancellationToken cancellationToken) {
        }
    }
}
