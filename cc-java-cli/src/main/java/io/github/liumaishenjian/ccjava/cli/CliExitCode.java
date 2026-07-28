package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import java.util.Objects;

/**
 * 定义 S02 人类可读 CLI 的稳定进程退出码。
 *
 * <p>退出码属于终端适配层，不进入 Runtime 的领域协议。交互 Session 中单个 Run
 * 被取消不会立即结束进程；Print 模式则把该 Run 的终态映射为这里的退出码。</p>
 *
 * @since 0.1.0
 */
public enum CliExitCode {

    /** 请求正常完成。 */
    SUCCESS(0),

    /** Picocli 参数语法错误。 */
    USAGE(2),

    /** Provider、环境变量或终端模式配置不可用。 */
    CONFIGURATION(3),

    /** 模型调用失败。 */
    MODEL_FAILURE(4),

    /** Runtime 达到明确预算或长度边界。 */
    LIMIT_REACHED(5),

    /** Runtime 以非预算类的可解释原因停止。 */
    RUNTIME_STOPPED(6),

    /** 用户取消 Print Run；数值与常见终端约定一致。 */
    CANCELLED(130),

    /** CLI 装配或未分类实现错误。 */
    INTERNAL_ERROR(70);

    private final int code;

    CliExitCode(int code) {
        this.code = code;
    }

    /**
     * 返回交给 Picocli 或 {@link System#exit(int)} 的数值。
     *
     * @return 稳定整数退出码
     */
    public int code() {
        return code;
    }

    /**
     * 把 Runtime 终态转换为 Print 模式退出码。
     *
     * @param result Runtime 的唯一终态
     * @return 对应 CLI 退出码
     */
    public static CliExitCode from(AgentRunResult result) {
        Objects.requireNonNull(result, "result 不能为空");
        StopReason reason = result.stopReason();
        return switch (reason) {
            case COMPLETED -> SUCCESS;
            case USER_CANCELLED -> CANCELLED;
            case MODEL_ERROR -> MODEL_FAILURE;
            case MODEL_OUTPUT_LIMIT_REACHED, TURN_LIMIT_REACHED,
                    TOOL_LIMIT_REACHED, TIME_LIMIT_REACHED,
                    CONTEXT_LIMIT_REACHED -> LIMIT_REACHED;
            case INTERNAL_ERROR -> INTERNAL_ERROR;
            default -> RUNTIME_STOPPED;
        };
    }
}
