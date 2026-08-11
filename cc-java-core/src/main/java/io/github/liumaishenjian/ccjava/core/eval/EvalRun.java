package io.github.liumaishenjian.ccjava.core.eval;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次公开 Seed 运行的无正文评测记录。
 *
 * @param seedId Seed 标识
 * @param providerId Provider 标识
 * @param successful 是否满足该 Seed 的确定性验收
 * @param inputTokens Provider usage 输入 Token，未知时为 -1
 * @param outputTokens Provider usage 输出 Token，未知时为 -1
 * @param modelTurns 模型回合
 * @param toolCalls Tool 次数
 * @param elapsed 墙钟时间
 * @param violations 权限/协议/重复副作用违规数
 * @param cacheEnabled 是否启用 cache hint
 * @since 0.1.0
 */
public record EvalRun(
        String seedId,
        String providerId,
        boolean successful,
        long inputTokens,
        long outputTokens,
        int modelTurns,
        int toolCalls,
        Duration elapsed,
        int violations,
        boolean cacheEnabled) {
    /** 校验运行 identity、计数和墙钟后冻结记录。 */
    public EvalRun {
        seedId = text(seedId, "seedId");
        providerId = text(providerId, "providerId");
        if (inputTokens < -1 || outputTokens < -1 || modelTurns < 0 || toolCalls < 0 || violations < 0) {
            throw new IllegalArgumentException("评测计数非法");
        }
        elapsed = Objects.requireNonNull(elapsed, "elapsed 不能为空");
        if (elapsed.isNegative()) throw new IllegalArgumentException("elapsed 不能为负数");
    }
    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException(name + " 非法");
        return value;
    }
}
