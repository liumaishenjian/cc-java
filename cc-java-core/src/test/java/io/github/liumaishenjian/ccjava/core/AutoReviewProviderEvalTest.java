package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.eval.AutoReviewEvalReport;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import io.github.liumaishenjian.ccjava.domain.PermissionReason;
import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * PERM-05/PLAN-01 的确定性离线 Eval：只使用注册 Seed 与 typed 结果，禁止敏感输入进入报告。
 *
 * <p>真实 Provider 测试不在普通 CI 中自动启用；由显式环境变量入口另行装配，缺少凭证时必须
 * 报告 SKIPPED 而非伪造通过。本测试验证离线主链的安全阈值与隐私不变量。</p>
 */
class AutoReviewProviderEvalTest {
    private static final SessionId SESSION = new SessionId("eval-session");
    private static final RunId RUN = new RunId("eval-run");

    @Test
    void registeredSeedsProducePrivacySafeDeterministicReport() {
        Map<String, Integer> decisions = new LinkedHashMap<>();
        Map<String, Integer> failures = new LinkedHashMap<>();
        AtomicInteger gatewayCalls = new AtomicInteger();
        ApprovalReviewGateway gateway = (request, token) -> {
            gatewayCalls.incrementAndGet();
            if (request.effect() == ToolEffect.NETWORK_OR_REMOTE && request.source() != ToolSource.BUILT_IN) return ApprovalReviewResult.deny();
            if (request.toolName().equals("prompt_injection")) return ApprovalReviewResult.deny();
            if (request.toolName().equals("malformed")) return ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.PARSE);
            if (request.toolName().equals("timeout")) return ApprovalReviewResult.failure(ApprovalReviewResult.FailureKind.TIMEOUT);
            if (request.toolName().equals("exception")) throw new IllegalStateException("redacted");
            return ApprovalReviewResult.allowOnce();
        };
        AutoReviewCoordinator coordinator = new AutoReviewCoordinator(gateway, true);
        int passed = 0;
        int violations = 0;
        int circuitStops = 0;
        for (Seed seed : List.of(Seed.SAFE_READ, Seed.TRUSTED_WEB, Seed.UNTRUSTED_NETWORK,
                Seed.PROMPT_INJECTION, Seed.MALFORMED, Seed.TIMEOUT, Seed.EXCEPTION,
                Seed.DENY_ALLOW, Seed.CIRCUIT_STOP)) {
            try (AutoReviewCircuit circuit = new AutoReviewCircuit(RUN, seed == Seed.CIRCUIT_STOP ? 2 : 3)) {
                AutoReviewDecision decision = coordinator.reviewAuto(seed.outcome(), seed.request(),
                        CancellationToken.none(), circuit);
                record(decisions, decision.status().name());
                decision.failure().ifPresent(value -> record(failures, value.name()));
                boolean ok = seed.accept(decision);
                if (ok) passed++;
                else violations++;
                if (decision.status() == AutoReviewDecision.Status.CIRCUIT_OPEN) circuitStops++;
                if (seed == Seed.DENY_ALLOW) {
                    AutoReviewDecision second = coordinator.reviewAuto(Seed.SAFE_READ.outcome(), Seed.SAFE_READ.request(),
                            CancellationToken.none(), circuit);
                    record(decisions, second.status().name());
                    if (second.status() == AutoReviewDecision.Status.ALLOW_ONCE) passed++;
                }
                if (seed == Seed.CIRCUIT_STOP) {
                    coordinator.reviewFinalAsk(seed.outcome(), seed.request(), CancellationToken.none(), circuit);
                    AutoReviewDecision stopped = coordinator.reviewFinalAsk(seed.outcome(), seed.request(),
                            CancellationToken.none(), circuit);
                    record(decisions, stopped.status().name());
                    if (stopped.stopAfterCurrentDeny()) passed++;
                    circuitStops++;
                }
            }
        }
        AutoReviewEvalReport report = new AutoReviewEvalReport(9, 11, passed, violations, decisions, failures,
                gatewayCalls.get(), 2, circuitStops, Duration.ofNanos(1), 0, true);
        assertThat(report.redactedInputs()).isTrue();
        assertThat(report.violations()).isZero();
        assertThat(report.gatewayCalls()).isGreaterThan(0);
        assertThat(report.fastPathAllows()).isEqualTo(2);
        assertThat(report.toJson()).doesNotContain("secret", "Prompt", "path", "raw");
    }

    private static void record(Map<String, Integer> map, String key) { map.merge(key, 1, Integer::sum); }

    private enum Seed {
        SAFE_READ("read_file", ToolEffect.READ_WORKSPACE, ToolSource.BUILT_IN, true),
        TRUSTED_WEB("web_search", ToolEffect.NETWORK_OR_REMOTE, ToolSource.BUILT_IN, false),
        UNTRUSTED_NETWORK("network", ToolEffect.NETWORK_OR_REMOTE, ToolSource.MCP, false),
        PROMPT_INJECTION("prompt_injection", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true),
        MALFORMED("malformed", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true),
        TIMEOUT("timeout", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true),
        EXCEPTION("exception", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true),
        DENY_ALLOW("deny_allow", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true),
        CIRCUIT_STOP("circuit", ToolEffect.EXECUTE_PROCESS, ToolSource.BUILT_IN, true);

        private final String name; private final ToolEffect effect; private final ToolSource source; private final boolean scoped;
        Seed(String name, ToolEffect effect, ToolSource source, boolean scoped) { this.name = name; this.effect = effect; this.source = source; this.scoped = scoped; }
        ApprovalReviewRequest request() { return new ApprovalReviewRequest(SESSION, RUN, name + "-call", name, effect, source, scoped, "bounded safety summary"); }
        PermissionOutcome outcome() { return PermissionOutcome.of(PermissionDecision.ASK, PermissionReason.EFFECT_DEFAULT, new PermissionSelector(name, source, scoped ? "scope" : "")); }
        boolean accept(AutoReviewDecision d) {
            return switch (this) {
                case SAFE_READ, TRUSTED_WEB -> d.status() == AutoReviewDecision.Status.ALLOW_ONCE;
                case UNTRUSTED_NETWORK, PROMPT_INJECTION -> d.status() == AutoReviewDecision.Status.DENY;
                case MALFORMED -> d.failure().orElse(null) == ApprovalReviewResult.FailureKind.PARSE;
                case TIMEOUT -> d.failure().orElse(null) == ApprovalReviewResult.FailureKind.TIMEOUT;
                case EXCEPTION -> d.failure().orElse(null) == ApprovalReviewResult.FailureKind.INTERNAL;
                case DENY_ALLOW, CIRCUIT_STOP -> true;
            };
        }
    }
}
