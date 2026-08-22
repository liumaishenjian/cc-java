package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceKind;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 规划期受控声明或纠正交付物和验证要求；不写 Workspace，也不解析 Markdown。
 *
 * <p>Tool 只能更新 Session-owned Ledger。DELIVERABLE locator 是后续由 WorkspaceGuard 验证的
 * 相对文件；VERIFICATION locator 必须同时满足稳定名称格式，并存在于当前 Runtime 注册的可信
 * BUILT_IN Tool 集合。相同 requirementId 在 DRAFT 中确定性原位替换，坏 locator 不会被提交或永久
 * 占用该身份。</p>
 *
 * @since 0.1.0
 */
public final class PlanEvidenceDeclarationTool implements AgentTool {
    /** 稳定 Tool 名。 */
    public static final String NAME = "declare_plan_evidence";
    private static final Set<String> FIELDS = Set.of("requirementId", "kind", "locator", "label", "required");
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Declare or correct one required deliverable or registered-tool verification item for deterministic completion validation.",
            """
            {"type":"object","additionalProperties":false,"required":["requirementId","kind","locator","label","required"],"properties":{"requirementId":{"type":"string","pattern":"^[a-z][a-z0-9-]{0,63}$"},"kind":{"type":"string","enum":["DELIVERABLE","VERIFICATION"]},"locator":{"type":"string","minLength":1,"maxLength":512},"label":{"type":"string","minLength":1,"maxLength":512},"required":{"type":"boolean"}}}
            """, ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false, Duration.ofSeconds(5),
            "text/plain", 256, Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final Clock clock;
    private final Set<String> trustedVerificationTools;

    /**
     * 绑定当前 Session 的 ledger store 与该 Runtime 实际注册的可信验证 Tool。
     *
     * @param store durable Plan store
     * @param sessionId 当前 Session
     * @param clock durable 时间源
     * @param trustedVerificationTools 当前 Runtime 注册、可在批准后执行的 BUILT_IN Tool 名
     */
    public PlanEvidenceDeclarationTool(PlanArtifactStore store,
            io.github.liumaishenjian.ccjava.domain.SessionId sessionId, Clock clock,
            Set<String> trustedVerificationTools) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        TreeSet<String> normalized = new TreeSet<>(Objects.requireNonNull(
                trustedVerificationTools, "trustedVerificationTools 不能为空"));
        if (normalized.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("可信验证 Tool 名无效");
        }
        this.trustedVerificationTools = Set.copyOf(normalized);
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            if (!arguments.values().keySet().equals(FIELDS)) {
                return ToolValidationResult.invalid("字段集合无效");
            }
            PlanEvidenceRequirement requirement = requirement(arguments);
            if (requirement.kind() == PlanEvidenceKind.VERIFICATION
                    && !trustedVerificationTools.contains(requirement.locator())) {
                return ToolValidationResult.invalid("VERIFICATION locator 未注册为可信 Tool；可用: "
                        + allowedAlternatives());
            }
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("证据要求无效；VERIFICATION locator 必须使用已注册可信 Tool 名");
        }
    }

    @Override public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        PlanArtifact current = store.load(sessionId).orElseThrow(
                () -> new PlanArtifactStoreException(PlanArtifactStoreException.Code.NOT_FOUND));
        if (current.status() != PlanStatus.DRAFT) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
        }
        PlanEvidenceRequirement requirement = requirement(invocation.call().arguments());
        if (requirement.kind() == PlanEvidenceKind.VERIFICATION
                && !trustedVerificationTools.contains(requirement.locator())) {
            throw new IllegalArgumentException("VERIFICATION locator 未注册");
        }
        var existingLedger = current.evidenceLedger();
        var ledger = existingLedger.declare(requirement, clock.instant());
        if (ledger == existingLedger) {
            return ToolExecutionOutcome.success("Plan evidence requirement already committed: "
                    + existingLedger.requirements().size());
        }
        PlanArtifact candidate = current.withEvidenceLedger(ledger, current.status(), clock.instant());
        PlanArtifact saved = store.save(candidate, current.revision(), current.contentDigest());
        return ToolExecutionOutcome.success("Plan evidence requirement committed: "
                + saved.evidenceLedger().requirements().size());
    }

    private String allowedAlternatives() {
        List<String> alternatives = trustedVerificationTools.stream().sorted().limit(12).toList();
        return alternatives.isEmpty() ? "none" : String.join(", ", alternatives);
    }

    private static PlanEvidenceRequirement requirement(JsonObject arguments) {
        Map<String, Object> values = arguments.values();
        Object required = values.get("required");
        if (!(required instanceof Boolean booleanValue)) throw new IllegalArgumentException("required 无效");
        return new PlanEvidenceRequirement(arguments.string("requirementId").orElseThrow(),
                PlanEvidenceKind.valueOf(arguments.string("kind").orElseThrow()),
                arguments.string("locator").orElseThrow(), arguments.string("label").orElseThrow(), booleanValue);
    }
}
