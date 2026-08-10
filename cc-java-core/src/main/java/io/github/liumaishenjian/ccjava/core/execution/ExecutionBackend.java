package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import java.io.IOException;

/**
 * 进程执行的唯一安全后端端口。
 *
 * <p>实现只拥有其亲自启动的进程树，不负责 Permission、Approval 或 Tool lifecycle。</p>
 *
 * @since 0.13.0
 */
public interface ExecutionBackend {
    /**
     * 返回后端稳定身份。
     *
     * @return 后端身份
     */
    ExecutionBackendId id();

    /**
     * 在后端安全边界内执行已获准的结构化请求。
     *
     * @param request 执行请求
     * @param cancellation 取消信号
     * @param outputSink 流式输出接收端
     * @return 包含终态和实际强制报告的结果
     * @throws IOException 后端启动、通信或清理失败
     */
    ExecutionOutcome execute(
            ExecutionRequest request,
            CancellationToken cancellation,
            ToolOutputSink outputSink) throws IOException;
}
