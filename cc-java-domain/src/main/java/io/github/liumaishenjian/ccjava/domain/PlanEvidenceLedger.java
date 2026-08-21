package io.github.liumaishenjian.ccjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * codej 独立增强：把批准 Plan 的预期交付、验证及真实证据绑定到 durable execution brief。
 *
 * <p>Ledger 在规划期只允许声明有界 requirement；批准时固定 approved revision、ExecutionBrief
 * 摘要与 Workspace revision；执行期只能由确定性验证器或显式用户 skip 决定写入引用。模型 prose、
 * Markdown checkbox 和最终回答都不能成为证据。</p>
 *
 * @param sessionId 所属 Session
 * @param planId 所属 Plan
 * @param approvedPlanRevision 未批准时为 0，批准后为用户批准的正文 revision
 * @param executionBriefDigest 未批准时为空，批准后为不可变 brief 摘要
 * @param approvedWorkspaceDigest 未批准时为空，批准后为审批时 Workspace revision
 * @param requirements 稳定声明顺序的要求
 * @param references 每项 requirement 的最新 durable 引用
 * @param createdAt Ledger 创建时间
 * @param updatedAt 最近 durable 更新
 * @since 0.1.0
 */
public record PlanEvidenceLedger(SessionId sessionId, String planId, long approvedPlanRevision,
                                 String executionBriefDigest, String approvedWorkspaceDigest,
                                 List<PlanEvidenceRequirement> requirements,
                                 List<PlanEvidenceReference> references,
                                 Instant createdAt, Instant updatedAt) {
    /** 单个 Plan 的独立 requirement 上限。 */
    public static final int MAX_REQUIREMENTS = 64;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    /** 验证绑定、唯一 requirement 和引用一致性。 */
    public PlanEvidenceLedger {
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        planId = Objects.requireNonNull(planId, "planId 不能为空");
        executionBriefDigest = Objects.requireNonNull(executionBriefDigest, "executionBriefDigest 不能为空");
        approvedWorkspaceDigest = Objects.requireNonNull(approvedWorkspaceDigest, "approvedWorkspaceDigest 不能为空");
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements 不能为空"));
        references = List.copyOf(Objects.requireNonNull(references, "references 不能为空"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (approvedPlanRevision < 0 || requirements.size() > MAX_REQUIREMENTS || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Ledger revision、数量或时间无效");
        }
        boolean bound = approvedPlanRevision > 0;
        if (bound != (SHA.matcher(executionBriefDigest).matches() && SHA.matcher(approvedWorkspaceDigest).matches())) {
            throw new IllegalArgumentException("Ledger execution 绑定无效");
        }
        Map<String, PlanEvidenceRequirement> byId = new LinkedHashMap<>();
        for (PlanEvidenceRequirement requirement : requirements) {
            if (byId.putIfAbsent(requirement.requirementId(), requirement) != null) {
                throw new IllegalArgumentException("requirementId 重复");
            }
        }
        Map<String, PlanEvidenceReference> refs = new LinkedHashMap<>();
        for (PlanEvidenceReference reference : references) {
            if (!byId.containsKey(reference.requirementId()) || refs.putIfAbsent(reference.requirementId(), reference) != null) {
                throw new IllegalArgumentException("证据引用缺少要求或重复");
            }
        }
    }

    /** 创建尚未批准、只含声明的 Ledger。 */
    public static PlanEvidenceLedger planning(SessionId sessionId, String planId, Instant now) {
        return new PlanEvidenceLedger(sessionId, planId, 0, "", "", List.of(), List.of(), now, now);
    }

    /** 在规划期幂等新增 requirement；批准后不可改变。 */
    public PlanEvidenceLedger declare(PlanEvidenceRequirement requirement, Instant now) {
        if (approvedPlanRevision != 0) throw new IllegalStateException("批准后不能修改证据要求");
        Objects.requireNonNull(requirement, "requirement 不能为空");
        Optional<PlanEvidenceRequirement> existing = requirements.stream()
                .filter(item -> item.requirementId().equals(requirement.requirementId())).findFirst();
        if (existing.isPresent()) {
            if (existing.orElseThrow().equals(requirement)) return this;
            throw new IllegalArgumentException("requirementId 已绑定不同要求");
        }
        ArrayList<PlanEvidenceRequirement> next = new ArrayList<>(requirements);
        next.add(requirement);
        return new PlanEvidenceLedger(sessionId, planId, 0, "", "", next, references, createdAt, monotonic(now));
    }

    /**
     * Workspace 漂移后清除旧审批与执行证据，同时保留用户仍可重新审批的 requirement 声明。
     *
     * @param now 重新打开审批的 durable 时间
     * @return 未绑定、无引用的新 Ledger
     */
    public PlanEvidenceLedger resetForReapproval(Instant now) {
        if (approvedPlanRevision == 0) throw new IllegalStateException("未绑定 Ledger 无需重置");
        return new PlanEvidenceLedger(sessionId, planId, 0, "", "", requirements, List.of(),
                createdAt, monotonic(now));
    }

    /** 在批准原子提交中固定 ExecutionBrief 与 Workspace revision。 */
    public PlanEvidenceLedger bind(long planRevision, String briefDigest, String workspaceDigest, Instant now) {
        if (approvedPlanRevision != 0 || planRevision < 1) throw new IllegalStateException("Ledger 已绑定或 revision 无效");
        return new PlanEvidenceLedger(sessionId, planId, planRevision, briefDigest, workspaceDigest,
                requirements, references, createdAt, monotonic(now));
    }

    /** 由确定性验证器写入或替换单项引用。 */
    public PlanEvidenceLedger record(PlanEvidenceReference reference, Instant now) {
        if (approvedPlanRevision == 0) throw new IllegalStateException("未批准 Ledger 不能记录证据");
        ArrayList<PlanEvidenceReference> next = new ArrayList<>(references);
        next.removeIf(item -> item.requirementId().equals(reference.requirementId()));
        next.add(Objects.requireNonNull(reference, "reference 不能为空"));
        return new PlanEvidenceLedger(sessionId, planId, approvedPlanRevision, executionBriefDigest,
                approvedWorkspaceDigest, requirements, next, createdAt, monotonic(now));
    }

    /** required 项全部 PASS 或显式 SKIP 时才允许完成，且至少声明一项 required evidence。 */
    public boolean completionSatisfied() {
        List<PlanEvidenceRequirement> required = requirements.stream().filter(PlanEvidenceRequirement::required).toList();
        if (required.isEmpty()) return false;
        Map<String, PlanEvidenceStatus> status = new LinkedHashMap<>();
        references.forEach(reference -> status.put(reference.requirementId(), reference.status()));
        return required.stream().allMatch(item -> {
            PlanEvidenceStatus value = status.get(item.requirementId());
            return value == PlanEvidenceStatus.PASSED || value == PlanEvidenceStatus.SKIPPED;
        });
    }

    /** 返回第一个阻止完成的稳定 requirement ID。 */
    public Optional<String> firstBlockingRequirement() {
        Map<String, PlanEvidenceStatus> status = new LinkedHashMap<>();
        references.forEach(reference -> status.put(reference.requirementId(), reference.status()));
        Optional<String> blocking = requirements.stream().filter(PlanEvidenceRequirement::required)
                .filter(item -> status.get(item.requirementId()) != PlanEvidenceStatus.PASSED
                        && status.get(item.requirementId()) != PlanEvidenceStatus.SKIPPED)
                .map(PlanEvidenceRequirement::requirementId).findFirst();
        if (blocking.isPresent()) return blocking;
        return requirements.stream().anyMatch(PlanEvidenceRequirement::required)
                ? Optional.empty() : Optional.of("required-evidence-not-declared");
    }

    private Instant monotonic(Instant now) {
        Instant checked = Objects.requireNonNull(now, "now 不能为空");
        return checked.isBefore(updatedAt) ? updatedAt : checked;
    }
}
