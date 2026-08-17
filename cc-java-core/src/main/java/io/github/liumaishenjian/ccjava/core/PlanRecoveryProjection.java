package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanExecutionState;
import java.util.Objects;

/** Session-owned Plan 的持久恢复投影；不包含可自动重放的操作。 */
public record PlanRecoveryProjection(PlanDocument document, PlanExecutionState state) {
    public PlanRecoveryProjection {
        document = Objects.requireNonNull(document, "document 不能为空");
        state = Objects.requireNonNull(state, "state 不能为空");
        if (!document.id().equals(state.planId())) throw new IllegalArgumentException("Plan ID 不匹配");
    }
}
