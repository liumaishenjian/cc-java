package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.PlanArtifact;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanToolCapability;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 将已提交 DRAFT 工件推进为 AWAITING_APPROVAL，并标记本次 Plan Run 已请求 review。
 *
 * <p>Tool 只接受当前 revision 与 digest，不能携带 Plan 正文、步骤或可执行参数。Surface review
 * 必须读取 {@link #reviewArtifact()} 返回的 durable revision。</p>
 *
 * @since 0.1.0
 */
public final class PlanReviewRequestTool implements AgentTool {
    /** 供模型调用的独立稳定名称。 */
    public static final String NAME = "request_plan_review";
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Submit the current durable Markdown plan for user review after all exploration and clarification is complete.",
            """
            {"type":"object","additionalProperties":false,"required":["revision","contentDigest"],"properties":{"revision":{"type":"integer","minimum":1},"contentDigest":{"type":"string","pattern":"^[0-9a-f]{64}$"}}}
            """,
            ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "text/plain", 256,
            Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final Clock clock;
    private volatile PlanArtifact reviewArtifact;

    /** 绑定当前 Session 的 durable 工件 store。 */
    public PlanReviewRequestTool(PlanArtifactStore store,
                                 io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                                 Clock clock) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        if (!arguments.values().keySet().equals(Set.of("revision", "contentDigest"))) {
            return ToolValidationResult.invalid("字段集合无效");
        }
        Object revision = arguments.values().get("revision");
        try {
            String digest = arguments.string("contentDigest").orElse("");
            if (!(revision instanceof Number number) || number.longValue() < 1
                    || number.doubleValue() != number.longValue() || !digest.matches("[0-9a-f]{64}")) {
                return ToolValidationResult.invalid("review CAS 参数无效");
            }
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("review CAS 参数无效");
        }
        return ToolValidationResult.validResult();
    }

    @Override
    public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        long revision = ((Number) invocation.call().arguments().values().get("revision")).longValue();
        String digest = invocation.call().arguments().string("contentDigest").orElseThrow();
        PlanArtifact current = store.load(sessionId).orElseThrow(
                () -> new PlanArtifactStoreException(PlanArtifactStoreException.Code.NOT_FOUND));
        if (current.status() != PlanStatus.DRAFT || current.revision() != revision
                || !current.contentDigest().equals(digest)) {
            throw new PlanArtifactStoreException(PlanArtifactStoreException.Code.DIGEST_CONFLICT);
        }
        PlanArtifact candidate = current.nextRevision(current.markdownContent(),
                PlanStatus.AWAITING_APPROVAL, clock.instant());
        reviewArtifact = store.save(candidate, revision, digest);
        return ToolExecutionOutcome.success("Plan review requested for revision %d".formatted(
                reviewArtifact.revision()));
    }

    /** 返回本次 Run 成功提交的 review revision。 */
    public synchronized java.util.Optional<PlanArtifact> reviewArtifact() {
        return java.util.Optional.ofNullable(reviewArtifact);
    }
}
