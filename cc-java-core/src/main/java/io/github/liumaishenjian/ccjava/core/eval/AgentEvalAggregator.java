package io.github.liumaishenjian.ccjava.core.eval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 对重复 Seed 运行计算完成率、安全违规、Token 与墙钟中位数。
 *
 * @since 0.1.0
 */
public final class AgentEvalAggregator {
    /** 创建无状态聚合器。 */
    public AgentEvalAggregator() {
    }

    /**
     * 聚合实际执行且不含正文的运行记录。
     *
     * @param input 同一评测批次的实际运行
     * @return 完成率、违规数、Token/墙钟中位数与 Provider 分布
     */
    public EvalReport aggregate(List<EvalRun> input) {
        List<EvalRun> runs = List.copyOf(Objects.requireNonNull(input, "input 不能为空"));
        int successes = (int) runs.stream().filter(EvalRun::successful).count();
        int violations = runs.stream().mapToInt(EvalRun::violations).sum();
        List<Long> tokens = runs.stream().map(EvalRun::inputTokens).filter(v -> v >= 0).sorted().toList();
        List<Long> elapsed = runs.stream().map(run -> run.elapsed().toNanos()).sorted().toList();
        LinkedHashMap<String, Integer> byProvider = new LinkedHashMap<>();
        runs.forEach(run -> byProvider.merge(run.providerId(), 1, Integer::sum));
        return new EvalReport(
                runs.size(), successes, violations,
                runs.isEmpty() ? 0 : (double) successes / runs.size(),
                tokens.isEmpty() ? -1 : median(tokens),
                Duration.ofNanos(elapsed.isEmpty() ? 0 : median(elapsed)),
                byProvider);
    }
    private static long median(List<Long> sorted) {
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return Math.round((sorted.get(middle - 1) / 2.0) + (sorted.get(middle) / 2.0));
    }
}
