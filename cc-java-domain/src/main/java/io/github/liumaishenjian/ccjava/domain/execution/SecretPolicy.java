package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.Objects;
import java.util.Set;

/**
 * 通用进程必须排除的 Secret 名称；值永不进入本对象或报告。
 *
 * @param deniedNames 被拒绝的环境或引用名称
 * @since 0.13.0
 */
public record SecretPolicy(Set<String> deniedNames) {
    public SecretPolicy {
        deniedNames = Set.copyOf(Objects.requireNonNull(deniedNames));
    }

    /**
     * 创建项目默认拒绝的常见 Secret 名称集合。
     *
     * @return 通用 Secret 策略
     */
    public static SecretPolicy common() {
        return new SecretPolicy(Set.of(
                "OPENAI_API_KEY",
                "ANTHROPIC_API_KEY",
                "AWS_SECRET_ACCESS_KEY",
                "GITHUB_TOKEN",
                "SSH_AUTH_SOCK"));
    }
}
