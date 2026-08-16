package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAuthApplicationServiceTest {
    @TempDir Path temporary;

    @Test
    void localListStatusModelsUseNoSecretReadOrNetworkAndRedactReferences() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "sentinel-env-value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());

        assertThat(fixture.service.listProviders(CancellationToken.none())).extracting(
                ProviderAuthApplicationService.ProviderSummary::providerId)
                .containsExactly("anthropic", "openrouter");
        assertThat(fixture.service.listProfiles(Optional.empty(), CancellationToken.none())).singleElement()
                .satisfies(profile -> {
                    assertThat(profile.refKind()).isEqualTo("ENV");
                    assertThat(profile.status()).isEqualTo(
                            io.github.liumaishenjian.ccjava.domain.model.ProviderAuthStatusCode.AVAILABLE_LOCAL);
                    assertThat(profile.toString()).doesNotContain("CC_TEST_ENV").doesNotContain("sentinel-env-value");
                });
        assertThat(fixture.service.listModels(Optional.of("anthropic"), CancellationToken.none()))
                .isNotEmpty().allSatisfy(model -> assertThat(model.providerId()).isEqualTo("anthropic"));
    }

    @Test
    void builtInModelOverlayPersistsAndUnknownProvidersStayTyped() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());

        fixture.service.addModel("anthropic", "overlay-model", true, CancellationToken.none());
        assertThat(fixture.service.listModels(Optional.of("anthropic"), CancellationToken.none()))
                .extracting(ProviderAuthApplicationService.ModelSummary::modelId).contains("overlay-model");
        assertThat(fixture.service.effectiveSelection()).get()
                .extracting(io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot::modelId)
                .isEqualTo("overlay-model");

        ProviderAuthApplicationService reopened = reopen(fixture, Map.of("CC_TEST_ENV", "value"));
        assertThat(reopened.effectiveSelection()).get()
                .extracting(io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot::modelId)
                .isEqualTo("overlay-model");
        assertThatThrownBy(() -> reopened.removeModel("custom-provider", "overlay-model", CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.PROVIDER_UNKNOWN));
        assertThatThrownBy(() -> reopened.listModels(Optional.of("missing"), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.PROVIDER_UNKNOWN));
    }

    @Test
    void compatibleProviderAddFailsClosedDuringActiveRunAndWritesOnlyAfterRunEnds() throws Exception {
        Fixture fixture = fixture(Map.of());
        var request = new ProviderAuthApplicationService.AddProviderRequest(
                "team-gateway", "Team Gateway", "https://gateway.example/v1", "model-x");

        try (ProviderAuthApplicationService.RunSelection ignored = fixture.service.beginRun()) {
            assertThatThrownBy(() -> fixture.service.addCompatibleProvider(request, CancellationToken.none()))
                    .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));
            assertThat(fixture.service.listProviders(CancellationToken.none()))
                    .extracting(ProviderAuthApplicationService.ProviderSummary::providerId)
                    .doesNotContain("team-gateway");
            assertThat(Files.exists(fixture.home().resolve(".cc-java/providers.v1.json"))).isFalse();
        }

        fixture.service.addCompatibleProvider(request, CancellationToken.none());
        assertThat(fixture.service.listProviders(CancellationToken.none()))
                .extracting(ProviderAuthApplicationService.ProviderSummary::providerId)
                .contains("team-gateway");
        assertThat(Files.exists(fixture.home().resolve(".cc-java/providers.v1.json"))).isTrue();
    }

    @Test
    void customProviderProfileAndModelSelectionPersistAcrossReopen() throws Exception {
        Map<String, String> environment = Map.of("CC_TEAM_GATEWAY_TOKEN", "fixture-token-value");
        Fixture fixture = fixture(environment);
        fixture.service.addCompatibleProvider(new ProviderAuthApplicationService.AddProviderRequest(
                "team-gateway", "Team Gateway", "https://gateway.example/v1", "model-x"),
                CancellationToken.none());
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest(
                "team-gateway", "default", ProviderAuthApplicationService.RefKind.ENV,
                "CC_TEAM_GATEWAY_TOKEN", true), null, CancellationToken.none());
        fixture.service.selectModel(new ProviderAuthApplicationService.ModelSelectionRequest(
                "team-gateway", "model-x", Optional.of("default"), true), CancellationToken.none());

        var providers = fixture.service.listProviders(CancellationToken.none());
        var profiles = fixture.service.listProfiles(Optional.of("team-gateway"), CancellationToken.none());
        var models = fixture.service.listModels(Optional.of("team-gateway"), CancellationToken.none());
        ProviderAuthApplicationService reopened = reopen(fixture, environment);

        assertThat(reopened.effectiveSelection()).get().satisfies(selection -> {
            assertThat(selection.providerId()).isEqualTo("team-gateway");
            assertThat(selection.profileId()).isEqualTo("default");
            assertThat(selection.modelId()).isEqualTo("model-x");
        });
        assertThat(reopened.listProviders(CancellationToken.none())).containsExactlyElementsOf(providers);
        assertThat(reopened.listProfiles(Optional.of("team-gateway"), CancellationToken.none()))
                .containsExactlyElementsOf(profiles);
        assertThat(reopened.listModels(Optional.of("team-gateway"), CancellationToken.none()))
                .containsExactlyElementsOf(models);
    }

    @Test
    void modelSelectionIsLkgAndRefusesChangesDuringActiveRun() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());
        String model = fixture.service.listModels(Optional.of("anthropic"), CancellationToken.none()).getFirst().modelId();
        var selected = fixture.service.selectModel(new ProviderAuthApplicationService.ModelSelectionRequest(
                "anthropic", model, Optional.empty(), true), CancellationToken.none());
        try (ProviderAuthApplicationService.RunSelection run = fixture.service.beginRun()) {
            assertThat(run.snapshot()).contains(selected);
            assertThatThrownBy(() -> fixture.service.selectModel(
                    new ProviderAuthApplicationService.ModelSelectionRequest(
                            "anthropic", "missing", Optional.of("personal"), false), CancellationToken.none()))
                    .isInstanceOf(ProviderAuthException.class);
            assertThat(fixture.service.nextSelection()).contains(selected);
        }
        assertThatThrownBy(() -> fixture.service.selectModel(
                new ProviderAuthApplicationService.ModelSelectionRequest(
                        "anthropic", "missing", Optional.of("personal"), false), CancellationToken.none()))
                .isInstanceOf(ProviderAuthException.class);
        assertThat(fixture.service.nextSelection()).contains(selected);
    }

    @Test
    void productionGatewayFenceRejectsSelectionUntilEveryTerminalPathCloses() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());
        String model = fixture.service.listModels(Optional.of("anthropic"), CancellationToken.none())
                .getFirst().modelId();
        ProviderAuthApplicationService.ModelSelectionRequest selection =
                new ProviderAuthApplicationService.ModelSelectionRequest(
                        "anthropic", model, Optional.of("personal"), false);
        fixture.service.selectModel(selection, CancellationToken.none());

        java.util.concurrent.atomic.AtomicInteger routeCloses = new java.util.concurrent.atomic.AtomicInteger();
        RunScopedModelGateway gateway = ProviderAuthRuntimeResources.fencedGateway(
                new RunScopedModelGateway() {
                    @Override public RunScope openRun() {
                        return new RunScope() {
                            @Override public void bindCancellation(Runnable cancellation) { }
                            @Override public void close() {
                                routeCloses.incrementAndGet();
                                throw new IllegalStateException("fixture route close");
                            }
                        };
                    }
                    @Override public io.github.liumaishenjian.ccjava.domain.ModelTurn complete(
                            io.github.liumaishenjian.ccjava.domain.ModelRequest request) {
                        return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unused");
                    }
                }, fixture.service);

        RunScopedModelGateway.RunScope run = gateway.openRun();
        assertThatThrownBy(() -> fixture.service.selectModel(selection, CancellationToken.none()))
                .isInstanceOf(ProviderAuthException.class);
        assertThatThrownBy(run::close).isInstanceOf(IllegalStateException.class);
        assertThat(routeCloses).hasValue(1);
        assertThat(fixture.service.selectModel(selection, CancellationToken.none())).isNotNull();
        run.close();
        assertThat(routeCloses).hasValue(1);
    }

    @Test
    void failedRouteOpenDoesNotLeakApplicationRunFence() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());
        String model = fixture.service.listModels(Optional.of("anthropic"), CancellationToken.none())
                .getFirst().modelId();
        var selection = new ProviderAuthApplicationService.ModelSelectionRequest(
                "anthropic", model, Optional.of("personal"), false);
        fixture.service.selectModel(selection, CancellationToken.none());
        RunScopedModelGateway gateway = ProviderAuthRuntimeResources.fencedGateway(
                new RunScopedModelGateway() {
                    @Override public RunScope openRun() { throw new IllegalStateException("fixture open"); }
                    @Override public io.github.liumaishenjian.ccjava.domain.ModelTurn complete(
                            io.github.liumaishenjian.ccjava.domain.ModelRequest request) {
                        return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unused");
                    }
                }, fixture.service);

        assertThatThrownBy(gateway::openRun).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.service.selectModel(selection, CancellationToken.none())).isNotNull();
    }

    @Test
    void storeLoginConsumesSecretAndLogoutOnlyDeletesLocalProfile() throws Exception {
        Fixture fixture = fixture(Map.of());
        char[] source = "application-secret-sentinel".toCharArray();
        var result = fixture.service.login(new ProviderAuthApplicationService.LoginRequest(
                "openrouter", "work", ProviderAuthApplicationService.RefKind.STORE, null, true),
                () -> new io.github.liumaishenjian.ccjava.cli.auth.SecretMaterial(source), CancellationToken.none());
        java.util.Arrays.fill(source, '\0');
        assertThat(result.toString()).doesNotContain("application-secret-sentinel");
        ProviderAuthApplicationService.LogoutResult logout = fixture.service.logout(
                "openrouter", "work", CancellationToken.none());
        assertThat(logout.remoteRevoked()).isFalse();
        assertThat(fixture.service.listProfiles(Optional.empty(), CancellationToken.none())).isEmpty();
    }

    @Test
    void logoutCancelsLastLeaseWaitsForTerminalAndKeepsProfileFenced() throws Exception {
        Fixture fixture = fixture(Map.of("CC_TEST_ENV", "value"));
        fixture.service.login(new ProviderAuthApplicationService.LoginRequest("anthropic", "personal",
                ProviderAuthApplicationService.RefKind.ENV, "CC_TEST_ENV", true), null, CancellationToken.none());
        var lease = fixture.leases.acquire("anthropic", "personal", 9, () -> { });
        java.util.concurrent.atomic.AtomicInteger cancellations = new java.util.concurrent.atomic.AtomicInteger();
        lease.bindCancellation(() -> {
            cancellations.incrementAndGet();
            lease.close();
        });

        ProviderAuthApplicationService.LogoutResult result = fixture.service.logout(
                "anthropic", "personal", CancellationToken.none());

        assertThat(result.remoteRevoked()).isFalse();
        assertThat(cancellations).hasValue(1);
        assertThat(fixture.leases.activeCount("anthropic", "personal")).isZero();
        assertThat(fixture.leases.fenced("anthropic", "personal")).isTrue();
        assertThat(fixture.service.listProfiles(Optional.empty(), CancellationToken.none())).isEmpty();
        assertThatThrownBy(() -> fixture.leases.acquire("anthropic", "personal", 9, () -> { }))
                .isInstanceOf(ProviderAuthException.class);
    }

    private Fixture fixture(Map<String, String> environment) throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home-" + Files.list(temporary).count()));
        Path repository = Files.createDirectory(temporary.resolve("repo-" + Files.list(temporary).count()));
        RestrictedFileCredentialStore credentials = new RestrictedFileCredentialStore(home);
        ProviderDefinitionStore definitions = new ProviderDefinitionStore(home);
        LegacyCredentialMigrationService migration = new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repository), definitions, credentials);
        var leases = new io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry();
        return new Fixture(new ProviderAuthApplicationService(
                definitions, credentials, migration, environment, leases), leases, home, repository);
    }

    private ProviderAuthApplicationService reopen(Fixture fixture, Map<String, String> environment) {
        RestrictedFileCredentialStore credentials = new RestrictedFileCredentialStore(fixture.home());
        ProviderDefinitionStore definitions = new ProviderDefinitionStore(fixture.home());
        LegacyCredentialMigrationService migration = new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(fixture.repository()), definitions, credentials);
        return new ProviderAuthApplicationService(definitions, credentials, migration, environment);
    }

    private record Fixture(ProviderAuthApplicationService service,
                           io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry leases,
                           Path home, Path repository) { }
}
