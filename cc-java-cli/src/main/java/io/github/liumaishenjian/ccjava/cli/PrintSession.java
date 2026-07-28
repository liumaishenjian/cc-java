package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import java.util.Objects;

/**
 * 执行一次不读取 stdin 的 Print Run。
 *
 * <p>Print 只调用一次 Runtime，并把确定性终态映射为稳定退出码。需要人工审批的路径
 * 由后续 Permission Stage 在 Runtime 中拒绝，CLI 不会弹出交互提示。</p>
 *
 * @since 0.1.0
 */
public final class PrintSession {

    private final CliRuntime runtime;
    private final TerminalRenderer renderer;

    /**
     * 创建 Print Session。
     *
     * @param runtime  同一 Runtime 引擎
     * @param renderer stdout/stderr 分离的渲染器
     */
    public PrintSession(CliRuntime runtime, TerminalRenderer renderer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.renderer = Objects.requireNonNull(renderer, "renderer 不能为空");
    }

    /**
     * 执行一次用户任务。
     *
     * @param prompt 非空任务文本
     * @return 稳定进程退出码
     */
    public int run(String prompt) {
        Objects.requireNonNull(prompt, "prompt 不能为空");
        if (prompt.isBlank()) {
            renderer.error("--print 的任务文本不能为空白");
            return CliExitCode.CONFIGURATION.code();
        }

        renderer.beginRun();
        try {
            AgentRunResult result = runtime.run(prompt, CancellationToken.none());
            renderer.completeRun(result);
            return CliExitCode.from(result).code();
        } catch (RuntimeException exception) {
            renderer.error("Runtime 执行失败");
            return CliExitCode.INTERNAL_ERROR.code();
        }
    }
}
