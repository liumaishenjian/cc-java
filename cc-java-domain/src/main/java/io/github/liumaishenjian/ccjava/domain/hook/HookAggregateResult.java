package io.github.liumaishenjian.ccjava.domain.hook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个生命周期点所有匹配 Handler 的确定性聚合结果。
 *
 * <p>结果顺序与绑定顺序相同；阻断/否决优先于允许，Context 按绑定顺序拼接。
 * 该对象只描述 Hook 意见，不能替代 Permission 或 Tool Pipeline 的最终决定。</p>
 *
 * @param event 事件种类
 * @param disposition 聚合意见
 * @param executions 每个匹配绑定的结果
 * @param additionalContext 有界的上下文增量
 * @param blockingReason 选中的阻断原因
 * @since 0.1.0
 */
public record HookAggregateResult(
        HookEventKind event,
        HookDisposition disposition,
        List<HookExecutionResult> executions,
        Optional<String> additionalContext,
        Optional<String> blockingReason) {

    /** 聚合上下文总字符上限。 */
    public static final int MAX_CONTEXT_CHARACTERS = 8_192;

    /**
     * 校验并冻结聚合结果。
     */
    public HookAggregateResult {
        event = Objects.requireNonNull(event, "event 不能为空");
        disposition = Objects.requireNonNull(disposition, "disposition 不能为空");
        executions = List.copyOf(Objects.requireNonNull(executions, "executions 不能为空"));
        additionalContext = Objects.requireNonNull(additionalContext, "additionalContext 不能为空")
                .map(text -> {
                    if (text.codePointCount(0, text.length()) > MAX_CONTEXT_CHARACTERS) {
                        throw new IllegalArgumentException("Hook Context 超过字符上限");
                    }
                    return text;
                });
        blockingReason = Objects.requireNonNull(blockingReason, "blockingReason 不能为空");
    }

    /** 返回没有匹配绑定的无效果结果。 */
    public static HookAggregateResult empty(HookEventKind event) {
        return new HookAggregateResult(
                event,
                HookDisposition.CONTINUE,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** 判断该聚合结果是否会阻止当前决策点。 */
    public boolean blocking() {
        return disposition == HookDisposition.BLOCK || disposition == HookDisposition.DENY;
    }
}
