package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.eval.AgentEvalAggregator;
import io.github.liumaishenjian.ccjava.core.eval.EvalReport;
import io.github.liumaishenjian.ccjava.core.eval.EvalRun;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyResolver;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyStatus;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyValue;
import io.github.liumaishenjian.ccjava.core.model.ModelCostCalculator;
import io.github.liumaishenjian.ccjava.core.model.ModelProviderRoute;
import io.github.liumaishenjian.ccjava.core.model.ProviderRoutePolicy;
import io.github.liumaishenjian.ccjava.core.model.ProviderRouter;
import io.github.liumaishenjian.ccjava.core.session.InMemorySessionIndex;
import io.github.liumaishenjian.ccjava.core.session.RetentionAction;
import io.github.liumaishenjian.ccjava.core.session.RetentionReason;
import io.github.liumaishenjian.ccjava.core.session.SessionExportPolicy;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import io.github.liumaishenjian.ccjava.core.session.SessionLifecycleStatus;
import io.github.liumaishenjian.ccjava.core.session.SessionRetentionPolicy;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetryExporter;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignal;
import io.github.liumaishenjian.ccjava.core.telemetry.TelemetrySignalKind;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.domain.governance.ManagedPolicyProvenance;
import io.github.liumaishenjian.ccjava.domain.model.CapabilitySupport;
import io.github.liumaishenjian.ccjava.domain.model.ModelCapability;
import io.github.liumaishenjian.ccjava.domain.model.ModelCost;
import io.github.liumaishenjian.ccjava.domain.model.ModelPrice;
import io.github.liumaishenjian.ccjava.domain.model.ModelProviderCapabilitySnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class S14ProductionCoreTest {

    @Test
    void capabilitySnapshotIsConservative() {
        var snapshot = ModelProviderCapabilitySnapshot.resolve(
                "p", "m",
                Map.of(ModelCapability.TEXT, CapabilitySupport.SUPPORTED,
                        ModelCapability.TOOL_CALLING, CapabilitySupport.SUPPORTED),
                Map.of(ModelCapability.TEXT, CapabilitySupport.SUPPORTED,
                        ModelCapability.TOOL_CALLING, CapabilitySupport.UNSUPPORTED));
        assertThat(snapshot.supports(ModelCapability.TEXT)).isTrue();
        assertThat(snapshot.supports(ModelCapability.TOOL_CALLING)).isFalse();
        assertThat(snapshot.effective().get(ModelCapability.PROMPT_CACHE))
                .isEqualTo(CapabilitySupport.UNKNOWN);
    }

    @Test
    void unknownPriceIsNotRepresentableAndKnownCostIsExact() {
        ModelCost cost = new ModelCostCalculator().calculate(
                new ModelUsage(1_000_000, 500_000, 1_500_000),
                new ModelPrice(new BigDecimal("3"), new BigDecimal("15"),
                        Currency.getInstance("USD"), "2026-08"));
        assertThat(cost.amount()).isEqualByComparingTo("10.5");
    }

    @Test
    void routerFallsBackOnlyBeforeVisibleDelta() throws Exception {
        var capA = capabilities("a", false);
        var capB = capabilities("b", false);
        StreamingModelGateway failing = (request, observer, cancellation) -> {
            throw new ModelGatewayException(ModelGatewayException.FailureKind.RETRYABLE, "retry");
        };
        StreamingModelGateway success = (request, observer, cancellation) -> completedTurn();
        ProviderRouter router = new ProviderRouter(List.of(
                new ModelProviderRoute("a", failing, capA),
                new ModelProviderRoute("b", success, capB)));
        assertThat(router.complete(request(List.of()), delta -> { }, CancellationToken.none())
                .assistantMessage().text()).isEqualTo("ok");

        StreamingModelGateway partial = (request, observer, cancellation) -> {
            observer.onTextDelta("visible");
            throw new ModelGatewayException(ModelGatewayException.FailureKind.RETRYABLE, "retry");
        };
        ProviderRouter blocked = new ProviderRouter(List.of(
                new ModelProviderRoute("a", partial, capA),
                new ModelProviderRoute("b", success, capB)));
        assertThatThrownBy(() -> blocked.complete(
                request(List.of()), delta -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class);
    }

    @Test
    void routerRequiresToolCapabilityAndObservesCancelOnNonStreamingGateway() {
        var textOnly = capabilities("a", false);
        var tools = capabilities("b", true);
        ModelGateway textGateway = request -> completedTurn();
        ModelGateway toolGateway = request -> completedTurn();
        ProviderRouter router = new ProviderRouter(List.of(
                new ModelProviderRoute("a", textGateway, textOnly),
                new ModelProviderRoute("b", toolGateway, tools)));
        ToolDefinition tool = ToolDefinition.readOnlyText(
                "read_file", "read", "{\"type\":\"object\"}");
        assertThatThrownBy(() -> router.complete(
                request(List.of(tool)), delta -> { }, new CancelledToken()))
                .isInstanceOf(ModelGatewayException.class)
                .extracting(failure -> ((ModelGatewayException) failure).kind())
                .isEqualTo(ModelGatewayException.FailureKind.CANCELLED);
    }

    @Test
    void routerHonorsSharedAttemptDeadlineAndCostBudget() {
        var capabilities = capabilities("a", false);
        ModelGateway failing = request -> {
            throw new ModelGatewayException(ModelGatewayException.FailureKind.RETRYABLE, "retry");
        };
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        ProviderRoutePolicy exhausted = new ProviderRoutePolicy(
                1, now.plusSeconds(10), Duration.ZERO, 1, 1,
                Clock.fixed(now, ZoneOffset.UTC));
        ProviderRouter router = new ProviderRouter(List.of(
                new ModelProviderRoute("a", failing, capabilities),
                new ModelProviderRoute("b", failing, capabilities("b", false))), exhausted);
        assertThatThrownBy(() -> router.complete(
                request(List.of()), delta -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class);

        ProviderRoutePolicy expired = new ProviderRoutePolicy(
                2, now, Duration.ZERO, -1, 0, Clock.fixed(now, ZoneOffset.UTC));
        ProviderRouter deadline = new ProviderRouter(
                List.of(new ModelProviderRoute("a", failing, capabilities)), expired);
        assertThatThrownBy(() -> deadline.complete(
                request(List.of()), delta -> { }, CancellationToken.none()))
                .isInstanceOf(ModelGatewayException.class)
                .extracting(failure -> ((ModelGatewayException) failure).kind())
                .isEqualTo(ModelGatewayException.FailureKind.CANCELLED);
    }

    @Test
    void defaultRouterGetsFreshDeadlineForEachComplete() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        ProviderRoutePolicy policy = new ProviderRoutePolicy(
                1, Duration.ofMinutes(5), Duration.ZERO, -1, 0, clock);
        AtomicReference<Instant> observed = new AtomicReference<>();
        ModelGateway gateway = request -> {
            observed.set(clock.instant());
            return completedTurn();
        };
        ProviderRouter router = new ProviderRouter(
                List.of(new ModelProviderRoute("a", gateway, capabilities("a", false))), policy);

        router.complete(request(List.of()), delta -> { }, CancellationToken.none());
        assertThat(observed.get()).isEqualTo(Instant.parse("2026-08-11T00:00:00Z"));
        clock.advance(Duration.ofMinutes(6));
        assertThat(router.complete(request(List.of()), delta -> { }, CancellationToken.none())
                .assistantMessage().text()).isEqualTo("ok");
        assertThat(observed.get()).isEqualTo(Instant.parse("2026-08-11T00:06:00Z"));
    }

    @Test
    void telemetryUsesClosedValueDomainsAndLowCardinalityBuckets() {
        assertThatThrownBy(() -> new TelemetrySignal(
                TelemetrySignalKind.RUN, Optional.of(Duration.ZERO), Map.of("prompt", "secret")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelemetrySignal(
                TelemetrySignalKind.RUN, Optional.of(Duration.ZERO),
                Map.of("provider", "C:/private/provider-token")))
                .isInstanceOf(IllegalArgumentException.class);
        String bucket = TelemetrySignal.lowCardinalityBucket("private-provider-name");
        TelemetryExporter exporter = TelemetryExporter.noop();
        exporter.export(new TelemetrySignal(
                TelemetrySignalKind.RUN, Optional.of(Duration.ZERO),
                Map.of("status", "completed", "provider", bucket)));
        assertThat(exporter.flush(Duration.ofMillis(1))).isTrue();
    }

    @Test
    void evalAggregatesRepeatedRuns() {
        List<EvalRun> runs = List.of(
                new EvalRun("s1", "p", true, 100, 10, 1, 0, Duration.ofMillis(10), 0, false),
                new EvalRun("s1", "p", false, 200, 10, 1, 0, Duration.ofMillis(20), 0, false));
        EvalReport report = new AgentEvalAggregator().aggregate(runs);
        assertThat(report.successRate()).isEqualTo(.5);
        assertThat(report.medianKnownInputTokens()).isEqualTo(150);
        assertThat(report.violations()).isZero();
    }

    @Test
    void retentionAndExportPolicyFailClosed() {
        SessionRetentionPolicy policy = new SessionRetentionPolicy();
        assertThat(policy.plan(
                SessionLifecycleStatus.ACTIVE, RetentionAction.PERMANENT_DELETE, true, true).allowed())
                .isFalse();
        assertThat(policy.plan(
                SessionLifecycleStatus.CLOSED, RetentionAction.PERMANENT_DELETE, true, false).reason())
                .isEqualTo(RetentionReason.CONFIRMATION_REQUIRED);
        assertThatThrownBy(() -> new SessionExportPolicy(true, false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenThousandSessionIndexMeetsFunctionalContract() {
        InMemorySessionIndex index = new InMemorySessionIndex();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10_000; i++) {
            index.upsert(new SessionIndexEntry(
                    "s" + i, "w", "name-" + i, base.plusSeconds(i),
                    SessionLifecycleStatus.CLOSED));
        }
        assertThat(index.list(0, 10)).hasSize(10);
        assertThat(index.search("name-9999", 10))
                .extracting(SessionIndexEntry::sessionId).containsExactly("s9999");
    }

    @Test
    void managedPolicyUsesTrustedLkgAndFailsClosedWithoutIt() {
        ManagedPolicyResolver resolver = new ManagedPolicyResolver();
        var provenance = new ManagedPolicyProvenance(
                1, "a".repeat(64), Instant.now(), true, true);
        var lkg = new ManagedPolicyValue(Set.of("remote"), true, true, provenance);
        assertThat(resolver.resolve(Optional.empty(), Optional.of(lkg), true, true).status())
                .isEqualTo(ManagedPolicyStatus.LKG);
        assertThat(resolver.resolve(Optional.empty(), Optional.empty(), true, true).status())
                .isEqualTo(ManagedPolicyStatus.FAIL_CLOSED);
    }

    private static ModelProviderCapabilitySnapshot capabilities(String provider, boolean tools) {
        Map<ModelCapability, CapabilitySupport> configured = tools
                ? Map.of(ModelCapability.TEXT, CapabilitySupport.SUPPORTED,
                        ModelCapability.TOOL_CALLING, CapabilitySupport.SUPPORTED)
                : Map.of(ModelCapability.TEXT, CapabilitySupport.SUPPORTED);
        return ModelProviderCapabilitySnapshot.resolve(provider, "m", configured, Map.of());
    }

    private static ModelRequest request(List<ToolDefinition> tools) {
        return new ModelRequest(
                new SessionId("s1"), new RunId("r1"), 1,
                List.of(new UserMessage("x")), tools);
    }

    private static ModelTurn completedTurn() {
        return new ModelTurn(
                new AssistantMessage("ok", List.of()),
                new ModelTurnMetadata(ModelFinishReason.STOP, Optional.empty(), Optional.empty()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class CancelledToken implements CancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return true;
        }

        @Override
        public Registration onCancellation(Runnable action) {
            action.run();
            return () -> { };
        }
    }
}
