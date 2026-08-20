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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 规划期受控声明交付物和验证要求；不写 Workspace，也不解析 Markdown。
 *
 * <p>Tool 只能更新 Session-owned Ledger。DELIVERABLE locator 是后续由 WorkspaceGuard 验证的
 * 相对文件，VERIFICATION locator 是后续匹配成功 Tool Result 的可信 Tool 名；不能携带命令或正文。</p>
 *
 * @since 0.1.0
 */
public final class PlanEvidenceDeclarationTool implements AgentTool {
    /** 稳定 Tool 名。 */
    public static final String NAME = "declare_plan_evidence";
    private static final Set<String> FIELDS = Set.of("requirementId", "kind", "locator", "label", "required");
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Declare one required deliverable or verification evidence item for deterministic completion validation.",
            """
            {"type":"object","additionalProperties":false,"required":["requirementId","kind","locator","label","required"],"properties":{"requirementId":{"type":"string","pattern":"^[a-z][a-z0-9-]{0,63}$"},"kind":{"type":"string","enum":["DELIVERABLE","VERIFICATION"]},"locator":{"type":"string","minLength":1,"maxLength":512},"label":{"type":"string","minLength":1,"maxLength":512},"required":{"type":"boolean"}}}
            """, ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false, Duration.ofSeconds(5),
            "text/plain", 256, Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final Clock clock;

    /** 绑定当前 Session 的 ledger store。 */
    public PlanEvidenceDeclarationTool(PlanArtifactStore store,
            io.github.liumaishenjian.ccjava.domain.SessionId sessionId, Clock clock) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolValidationResult validate(JsonObject arguments) {
        try {
            if (!arguments.values().keySet().equals(FIELDS)) return ToolValidationResult.invalid("字段集合无效");
            requirement(arguments);
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("证据要求无效");
        }
    }

    @Override public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        PlanArtifact current = store.load(sessionId).orElseThrow(
                () -> new PlanArtifactStoreException(PlanArtifactStoreException.Code.NOT_FOUND));
        if (current.status() != PlanStatus.DRAFT) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.INVALID_STATE);
        }
        var ledger = current.evidenceLedger().declare(requirement(invocation.call().arguments()), clock.instant());
        PlanArtifact candidate = current.withEvidenceLedger(ledger, current.status(), clock.instant());
        PlanArtifact saved = store.save(candidate, current.revision(), current.contentDigest());
        return ToolExecutionOutcome.success("Plan evidence requirement committed: "
                + saved.evidenceLedger().requirements().size());
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
