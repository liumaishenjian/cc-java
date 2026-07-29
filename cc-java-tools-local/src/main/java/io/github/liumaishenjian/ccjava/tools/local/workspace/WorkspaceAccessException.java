package io.github.liumaishenjian.ccjava.tools.local.workspace;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import java.util.Objects;

/**
 * WorkspaceGuard 在任何文件内容被读取前产生的安全、结构化拒绝。
 *
 * <p>异常只携带可以反馈给模型的 {@link ToolError}，不得把绝对路径、Secret 或原始 I/O
 * 异常拼入错误信息。Tool 负责把它转换成普通失败 Outcome，使模型可以纠正调用。</p>
 *
 * @since 0.3.0
 */
public final class WorkspaceAccessException extends Exception {

    /** 可安全反馈给模型的结构化错误。 */
    private final ToolError error;

    /**
     * 创建安全访问异常。
     *
     * @param error 可反馈给模型的结构化错误
     */
    public WorkspaceAccessException(ToolError error) {
        super(Objects.requireNonNull(error, "error 不能为空").message());
        this.error = error;
    }

    /**
     * 返回结构化错误。
     *
     * @return 不含底层路径和异常的错误
     */
    public ToolError error() {
        return error;
    }
}
