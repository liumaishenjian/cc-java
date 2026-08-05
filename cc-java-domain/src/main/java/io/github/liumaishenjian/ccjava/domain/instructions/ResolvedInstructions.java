package io.github.liumaishenjian.ccjava.domain.instructions;

import java.util.List;
import java.util.Objects;

/**
 * 一次完整发现后可原子发布的指令投影与诊断。
 *
 * @param items 按低到高优先级排序的内部正文项
 * @param diagnostics 本次发现的安全诊断
 * @param revision 当前完整结果的稳定 revision
 * @since 0.8.0
 */
public record ResolvedInstructions(
        List<ResolvedInstruction> items,
        List<InstructionDiagnostic> diagnostics,
        InstructionRevision revision) {

    /** 防御性复制并校验确定性优先级顺序。 */
    public ResolvedInstructions {
        items = List.copyOf(Objects.requireNonNull(items, "items 不能为空"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        revision = Objects.requireNonNull(revision, "revision 不能为空");
        int prior = -1;
        for (ResolvedInstruction item : items) {
            int current = item.provenance().precedence();
            if (current < prior) {
                throw new IllegalArgumentException("items 必须按 precedence 非递减排列");
            }
            prior = current;
        }
    }
}
