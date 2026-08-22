package io.github.liumaishenjian.ccjava.core;

import java.util.Objects;

/**
 * 表示最终 Assistant 回合在形成 Run 终态前的确定性处理决定。
 *
 * <p>{@link Outcome#CONTINUE} 只允许宿主在同一个 Agent Run 内请求下一模型回合；它不会
 * 自动执行 Tool、重放既有副作用或接受当前模型 prose。宿主必须通过独立短生命周期投影向
 * 下一回合提供有界纠正原因，并由既有预算、取消和 Tool Pipeline 继续治理。</p>
 *
 * @param outcome 接受、拒绝或继续同一 Run
 * @since 0.1.0
 */
public record FinalAssistantDecision(Outcome outcome) {
    /** 最终 Assistant 的三种确定性处理结果。 */
    public enum Outcome {
        ACCEPT,
        REJECT,
        CONTINUE
    }

    /** 验证非空决定。 */
    public FinalAssistantDecision {
        outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
    }

    /** 接受当前最终 Assistant 并形成正常完成终态。 */
    public static FinalAssistantDecision accept() {
        return new FinalAssistantDecision(Outcome.ACCEPT);
    }

    /** 拒绝当前最终 Assistant，并按无效模型响应停止。 */
    public static FinalAssistantDecision reject() {
        return new FinalAssistantDecision(Outcome.REJECT);
    }

    /** 暂不接受当前 prose，在同一 Run 内继续下一模型回合。 */
    public static FinalAssistantDecision continueRun() {
        return new FinalAssistantDecision(Outcome.CONTINUE);
    }
}
