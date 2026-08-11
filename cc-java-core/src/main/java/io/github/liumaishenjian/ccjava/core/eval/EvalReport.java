package io.github.liumaishenjian.ccjava.core.eval;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 不含 Prompt、输出或文件正文的统一评测聚合。
 *
 * @param runs 实际执行的总次数
 * @param successes 成功终态次数
 * @param violations 安全或协议违规总数
 * @param successRate 成功次数除以总次数；空集合为零
 * @param medianKnownInputTokens 已知输入 Token 中位数；无已知值时为 -1
 * @param medianElapsed 单次运行墙钟中位数
 * @param runsByProvider 按实际 Provider identity 计数的运行次数
 */
public record EvalReport(
        int runs,
        int successes,
        int violations,
        double successRate,
        long medianKnownInputTokens,
        Duration medianElapsed,
        Map<String, Integer> runsByProvider) {
    /** 校验计数、比率与 Provider 分布后冻结报告。 */
    public EvalReport {
        if (runs < 0 || successes < 0 || successes > runs || violations < 0) {
            throw new IllegalArgumentException("评测聚合非法");
        }
        if (successRate < 0 || successRate > 1) {
            throw new IllegalArgumentException("successRate 非法");
        }
        if (medianKnownInputTokens < -1) {
            throw new IllegalArgumentException("median token 非法");
        }
        medianElapsed = Objects.requireNonNull(medianElapsed, "medianElapsed 不能为空");
        runsByProvider = Map.copyOf(Objects.requireNonNull(runsByProvider, "runsByProvider 不能为空"));
    }
}
