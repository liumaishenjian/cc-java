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
 * 以 revision/CAS 语义替换当前 Session 的受控 Markdown PlanArtifact。
 *
 * <p>Tool 不接受路径、Session ID 或 Plan ID；所有权由构造时绑定的 store 决定。创建必须携带
 * expectedRevision=0/空摘要，后续更新必须同时匹配当前 revision 与 digest。成功结果只返回新的
 * 安全版本摘要，不回显 Tool payload。</p>
 *
 * @since 0.1.0
 */
public final class PlanArtifactUpdateTool implements AgentTool {
    /** 供模型调用的独立稳定名称。 */
    public static final String NAME = "revise_plan_artifact";
    private static final Set<String> FIELDS = Set.of(
            "markdown", "expectedRevision", "expectedContentDigest");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Create or replace the current session's Markdown plan using revision and content-digest compare-and-set. This is the only write allowed while planning.",
            """
            {"type":"object","additionalProperties":false,"required":["markdown","expectedRevision","expectedContentDigest"],"properties":{"markdown":{"type":"string","minLength":1,"maxLength":1048576},"expectedRevision":{"type":"integer","minimum":0},"expectedContentDigest":{"type":"string","maxLength":64}}}
            """,
            ToolEffect.PLAN_ARTIFACT_WRITE, ToolSource.BUILT_IN, false,
            Duration.ofSeconds(5), "text/plain", 512,
            Set.of(PlanToolCapability.PLAN_ARTIFACT_WRITE));

    private final PlanArtifactStore store;
    private final io.github.liumaishenjian.ccjava.domain.SessionId sessionId;
    private final String planId;
    private final Clock clock;
    private volatile PlanArtifact latest;

    /** 绑定单个 Session 与稳定 Plan 身份。 */
    public PlanArtifactUpdateTool(PlanArtifactStore store,
                                  io.github.liumaishenjian.ccjava.domain.SessionId sessionId,
                                  String planId, Clock clock) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.planId = Objects.requireNonNull(planId, "planId 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.latest = store.load(sessionId).orElse(null);
        if (latest != null && !latest.planId().equals(planId)) {
            throw new IllegalArgumentException("PlanArtifact 身份与当前规划不匹配");
        }
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolValidationResult validate(JsonObject arguments) {
        try {
            if (!arguments.values().keySet().equals(FIELDS)) return ToolValidationResult.invalid("字段集合无效");
            String markdown = arguments.string("markdown").orElse("");
            Object revision = arguments.values().get("expectedRevision");
            String digest = arguments.string("expectedContentDigest").orElse("");
            if (!(revision instanceof Number number) || number.longValue() < 0
                    || number.doubleValue() != number.longValue()) {
                return ToolValidationResult.invalid("expectedRevision 无效");
            }
            if ((number.longValue() == 0 && !digest.isEmpty())
                    || (number.longValue() > 0 && !digest.matches("[0-9a-f]{64}"))) {
                return ToolValidationResult.invalid("expectedContentDigest 无效");
            }
            if (number.longValue() == 0) {
                PlanArtifact.create(planId, sessionId, markdown, PlanStatus.DRAFT, clock.instant());
            } else if (latest == null) {
                return ToolValidationResult.invalid("PlanArtifact 尚未创建");
            } else {
                latest.nextRevision(markdown, PlanStatus.DRAFT, clock.instant());
            }
            return ToolValidationResult.validResult();
        } catch (RuntimeException invalid) {
            return ToolValidationResult.invalid("PlanArtifact 参数无效");
        }
    }

    @Override
    public synchronized ToolExecutionOutcome execute(ToolInvocation invocation) {
        JsonObject arguments = invocation.call().arguments();
        String markdown = arguments.string("markdown").orElseThrow();
        long expectedRevision = ((Number) arguments.values().get("expectedRevision")).longValue();
        String expectedDigest = arguments.string("expectedContentDigest").orElseThrow();
        PlanArtifact candidate = expectedRevision == 0
                ? PlanArtifact.create(planId, sessionId, markdown, PlanStatus.DRAFT, clock.instant())
                : Objects.requireNonNull(latest, "PlanArtifact 尚未创建")
                        .nextRevision(markdown, PlanStatus.DRAFT, clock.instant());
        latest = store.save(candidate, expectedRevision, expectedDigest);
        return ToolExecutionOutcome.success("Plan artifact revision %d committed (%s)".formatted(
                latest.revision(), latest.contentDigest()));
    }

    /** 返回当前 Run 已提交的最新工件。 */
    public synchronized java.util.Optional<PlanArtifact> latest() {
        return java.util.Optional.ofNullable(latest);
    }
}
