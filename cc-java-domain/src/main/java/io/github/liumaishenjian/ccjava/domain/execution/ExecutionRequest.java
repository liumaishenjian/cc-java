package io.github.liumaishenjian.ccjava.domain.execution;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 已通过 Tool Pipeline 的结构化进程意图。backend 与 fallback 不由请求选择。
 *
 * @param callId 当前 Tool Call 身份
 * @param shell 显式命令语义
 * @param executable 固定 argv 模式的 executable，shell 文本模式为空
 * @param arguments 固定 argv 或单个 shell 正文
 * @param workingDirectoryIdentity canonical cwd identity
 * @param timeout 期限
 * @param outputCharacterLimit 输出上限
 * @param policy 有效策略
 * @since 0.13.0
 */
public record ExecutionRequest(
        String callId,
        ExecutionShell shell,
        String executable,
        List<String> arguments,
        String workingDirectoryIdentity,
        Duration timeout,
        int outputCharacterLimit,
        ExecutionPolicy policy) {
    /** 校验 argv、cwd、deadline、输出上限和调用 identity。 */
    public ExecutionRequest {
        callId = requireText(callId);
        shell = Objects.requireNonNull(shell);
        executable = Objects.requireNonNull(executable);
        arguments = List.copyOf(Objects.requireNonNull(arguments));
        workingDirectoryIdentity = requireText(workingDirectoryIdentity);
        timeout = Objects.requireNonNull(timeout);
        policy = Objects.requireNonNull(policy);
        if (timeout.isZero() || timeout.isNegative() || outputCharacterLimit < 1) {
            throw new IllegalArgumentException("执行预算无效");
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        return value;
    }
}
