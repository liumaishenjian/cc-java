package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.settings.SettingsFixedSourceLoader;
import io.github.liumaishenjian.ccjava.cli.settings.SettingsV1SourceParser;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.settings.SettingsResolver;
import io.github.liumaishenjian.ccjava.core.settings.SettingsSnapshotStore;
import io.github.liumaishenjian.ccjava.core.settings.RuntimeSettingsApplier;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsApplicationServiceTest {
    @TempDir Path root;

    @Test
    void refreshUsesFixedPrecedenceAndOverlaysRestoreLowerValuesWithoutRereading() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Files.createDirectories(workspace.resolve(".cc-java"));
        Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"permission\":{\"mode\":\"PLAN\"}}");
        Files.writeString(workspace.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"permission\":{\"mode\":\"ACCEPT_EDITS\"}}");
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsApplicationService service = service(runtime, home);
            assertThat(service.refresh(CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
            assertThat(service.replaceSessionOverlay(Optional.of(declaredMode("PLAN")), CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.PLAN);
            assertThat(service.replaceCliOverlay(Optional.of(declaredMode("DEFAULT")), CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.DEFAULT);
            Files.delete(workspace.resolve(".cc-java/settings.json"));
            assertThat(service.replaceCliOverlay(Optional.empty(), CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.PLAN);
            assertThat(service.replaceSessionOverlay(Optional.empty(), CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        }
    }

    @Test
    void sessionPatchPreservesOtherOverlayFieldsAndCliPrecedenceWithoutFixedReads() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            AtomicInteger calls = new AtomicInteger();
            SettingsApplicationService service = new SettingsApplicationService(runtime, countingLoader(home, runtime, calls),
                    runtime.builtinToolRegistry());
            DeclaredSettings session = new DeclaredSettings(Optional.of("fake-model"), Optional.of("PLAN"), List.of(),
                    Optional.of(List.of("read_file")), Map.of(), List.of("retain"), Optional.of("DETAIL"));
            assertThat(service.replaceSessionOverlay(Optional.of(session), CancellationToken.none()).published()).isTrue();
            int callsBeforePatch = calls.get();
            assertThat(service.patchSessionOverlay(new io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch.PermissionModeChange(PermissionMode.DEFAULT),
                    CancellationToken.none()).published()).isTrue();
            var patched = service.current().orElseThrow().settings();
            assertThat(patched.modelName().orElseThrow().value()).isEqualTo("fake-model");
            assertThat(patched.permissionMode().orElseThrow().value()).isEqualTo("DEFAULT");
            assertThat(patched.permissionRules()).isEmpty();
            assertThat(patched.enabledTools().orElseThrow().value()).extracting(value -> value.value()).containsExactly("read_file");
            assertThat(patched.toolConfigurations()).isEmpty();
            assertThat(patched.compactInstructions()).extracting(value -> value.instruction()).containsExactly("retain");
            assertThat(patched.diagnosticsVerbosity().orElseThrow().value()).isEqualTo("DETAIL");
            assertThat(calls).hasValue(callsBeforePatch);
            assertThat(service.replaceCliOverlay(Optional.of(declaredMode("PLAN")), CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.PLAN);
            assertThat(service.patchSessionOverlay(new io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch.PermissionModeChange(PermissionMode.ACCEPT_EDITS),
                    CancellationToken.none()).published()).isTrue();
            assertThat(runtime.runtimeConfiguration().permissionMode()).isEqualTo(PermissionMode.PLAN);
        }
    }

    @Test
    void cancellationAtCommitBoundaryRetainsLkgRuntimeScopeAndSessionOverlay() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsApplicationService service = new SettingsApplicationService(runtime,
                    countingLoader(home, runtime, new AtomicInteger()), runtime.builtinToolRegistry());
            assertThat(service.replaceSessionOverlay(Optional.of(declaredMode("PLAN")), CancellationToken.none()).published()).isTrue();
            var lkg = service.current().orElseThrow();
            var scope = runtime.runtimeConfiguration();
            AtomicInteger checks = new AtomicInteger();
            CancellationToken cancelledBeforeCommit = new CancellationToken() {
                @Override public boolean isCancellationRequested() { return checks.incrementAndGet() >= 6; }
                @Override public Registration onCancellation(Runnable action) { return () -> { }; }
            };

            var result = service.patchSessionOverlay(
                    new io.github.liumaishenjian.ccjava.domain.settings.SessionSettingsPatch.PermissionModeChange(PermissionMode.ACCEPT_EDITS),
                    cancelledBeforeCommit);

            assertThat(result.published()).isFalse();
            assertThat(result.diagnostics()).singleElement().hasToString(
                    "ConfigurationFailure[code=CANCELLED, sourceKind=DEFAULTS, fieldPath=Optional.empty]");
            assertThat(service.current()).contains(lkg);
            assertThat(runtime.runtimeConfiguration()).isSameAs(scope);
        }
    }

    @Test
    void currentReadsPublishedLkgWithoutInvokingFixedSourceLoader() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            AtomicInteger calls = new AtomicInteger();
            SettingsApplicationService service = new SettingsApplicationService(runtime, countingLoader(home, runtime, calls),
                    runtime.builtinToolRegistry());

            assertThat(service.current()).isEmpty();
            assertThat(calls).hasValue(0);
            service.refresh(CancellationToken.none());
            int callsAfterRefresh = calls.get();

            assertThat(service.current()).isPresent();
            assertThat(service.current()).isPresent();
            assertThat(calls).hasValue(callsAfterRefresh);
        }
    }

    @Test
    void cancellationAndInvalidRefreshRetainExactLastKnownGood() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"permission\":{\"mode\":\"PLAN\"}}");
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsApplicationService service = service(runtime, home);
            assertThat(service.refresh(CancellationToken.none()).published()).isTrue();
            var lkg = service.current().orElseThrow();
            Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"permission\":{\"mode\":true}}");
            assertThat(service.refresh(CancellationToken.none()).published()).isFalse();
            assertThat(service.current()).contains(lkg);
            CancellationSource cancelled = new CancellationSource(); cancelled.cancel();
            assertThat(service.refresh(cancelled.token()).published()).isFalse();
            assertThat(service.current()).contains(lkg);
        }
    }

    @Test
    void activeRunReturnsTypedFailureBeforeAnyFixedLoaderInvocation() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try (HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(request -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return ModelTurn.text("done");
        }, AgentEventSink.noop(), options(workspace))) {
            runtime.open();
            AtomicInteger calls = new AtomicInteger();
            SettingsFixedSourceLoader loader = countingLoader(home, runtime, calls);
            SettingsApplicationService service = new SettingsApplicationService(runtime, loader, runtime.builtinToolRegistry());
            Thread run = Thread.ofPlatform().start(() -> runtime.run("hold"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var rejected = service.refresh(CancellationToken.none());
            assertThat(rejected.published()).isFalse();
            assertThat(rejected.diagnostics().toString()).contains("ACTIVE_RUN");
            assertThat(calls.get()).isZero();
            release.countDown(); run.join(5_000);
        }
    }

    @Test
    void casConflictPreservesPriorLkgAndRuntimeScope() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsSnapshotStore store = new SettingsSnapshotStore() {
                @Override
                public boolean replaceIfCurrent(Optional<Long> expectedRevision,
                                                io.github.liumaishenjian.ccjava.core.settings.EffectiveSettingsSnapshot replacement) {
                    return false;
                }
            };
            SettingsApplicationService service = service(runtime, countingLoader(home, runtime, new AtomicInteger()), store);
            var before = runtime.runtimeConfiguration();

            var result = service.replaceSessionOverlay(Optional.of(declaredMode("PLAN")), CancellationToken.none());

            assertThat(result.published()).isFalse();
            assertThat(result.diagnostics()).singleElement().hasToString("ConfigurationFailure[code=CAS_CONFLICT, sourceKind=DEFAULTS, fieldPath=Optional.empty]");
            assertThat(service.current()).isEmpty();
            assertThat(runtime.runtimeConfiguration()).isSameAs(before);
        }
    }

    @Test
    void storeThrowPreservesPriorLkgAndRuntimeScope() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsSnapshotStore store = new SettingsSnapshotStore() {
                @Override
                public boolean replaceIfCurrent(Optional<Long> expectedRevision,
                                                io.github.liumaishenjian.ccjava.core.settings.EffectiveSettingsSnapshot replacement) {
                    throw new IllegalStateException("store callback failure");
                }
            };
            SettingsApplicationService service = service(runtime, countingLoader(home, runtime, new AtomicInteger()), store);
            var before = runtime.runtimeConfiguration();

            var result = service.replaceSessionOverlay(Optional.of(declaredMode("PLAN")), CancellationToken.none());

            assertThat(result.published()).isFalse();
            assertThat(result.diagnostics()).hasSize(1);
            assertThat(result.diagnostics().getFirst().toString()).isEqualTo("RuntimeFailure[code=INTERNAL_FAILURE]");
            assertThat(service.current()).isEmpty();
            assertThat(runtime.runtimeConfiguration()).isSameAs(before);
        }
    }

    @Test
    void concurrentRefreshesSerializeFixedSourceLoadingWithoutHoldingLifecycleMonitor() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(home.resolve(".cc-java"));
        Files.writeString(home.resolve(".cc-java/settings.json"), "{\"schemaVersion\":1,\"permission\":{\"mode\":\"PLAN\"}}");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch runFinished = new CountDownLatch(1);
        CountDownLatch secondRefreshStarted = new CountDownLatch(1);
        AtomicInteger userLoadCalls = new AtomicInteger();
        AtomicInteger activeLoads = new AtomicInteger();
        AtomicInteger maxConcurrentLoads = new AtomicInteger();
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsFixedSourceLoader loader = blockingLoader(
                    home, runtime, firstEntered, release, userLoadCalls, activeLoads, maxConcurrentLoads);
            SettingsApplicationService service = new SettingsApplicationService(runtime, loader, runtime.builtinToolRegistry());
            AtomicReference<SettingsApplicationService.SettingsApplicationResult> first = new AtomicReference<>();
            AtomicReference<SettingsApplicationService.SettingsApplicationResult> second = new AtomicReference<>();
            Thread firstThread = Thread.ofPlatform().start(() -> first.set(service.refresh(CancellationToken.none())));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Thread runThread = Thread.ofPlatform().start(() -> {
                try {
                    runtime.run("lifecycle operation while settings loader is blocked");
                } catch (Throwable failure) {
                    runFailure.set(failure);
                } finally {
                    runFinished.countDown();
                }
            });
            assertThat(runFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runFailure.get()).isNull();

            Thread secondThread = Thread.ofPlatform().start(() -> {
                secondRefreshStarted.countDown();
                second.set(service.refresh(CancellationToken.none()));
            });
            assertThat(secondRefreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(userLoadCalls.get()).isOne();
            assertThat(activeLoads.get()).isOne();
            assertThat(maxConcurrentLoads.get()).isOne();
            assertThat(second.get()).isNull();

            release.countDown();
            firstThread.join(5_000);
            secondThread.join(5_000);
            runThread.join(5_000);

            assertThat(firstThread.isAlive()).isFalse();
            assertThat(secondThread.isAlive()).isFalse();
            assertThat(runThread.isAlive()).isFalse();
            assertThat(first.get().published()).isTrue();
            assertThat(second.get().published()).isTrue();
            assertThat(maxConcurrentLoads.get()).isOne();
        }
    }

    @Test
    void missingFixedSourcesPublishOnlyInMemoryDefaultsWithoutCreatingSettingsFiles() throws Exception {
        Path home = root.resolve("absent-home");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsApplicationService service = service(runtime, home);

            var result = service.refresh(CancellationToken.none());

            assertThat(result.published()).isTrue();
            assertThat(result.diagnostics()).isEmpty();
            assertThat(service.current()).isPresent();
            assertThat(home).doesNotExist();
            assertThat(workspace.resolve(".cc-java")).doesNotExist();
            assertThat(root.resolve("sessions").resolve("settings.json")).doesNotExist();
        }
    }

    @Test
    void resultsAndDiagnosticsRedactValuesAndPaths() throws Exception {
        Path home = Files.createDirectories(root.resolve("private-home"));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        try (HeadlessRuntimeSession runtime = runtime(workspace)) {
            runtime.open();
            SettingsApplicationService service = service(runtime, home);
            var result = service.replaceSessionOverlay(Optional.of(declaredMode("NOT_A_MODE")), CancellationToken.none());
            assertThat(result.toString()).doesNotContain(home.toString(), workspace.toString(), "NOT_A_MODE");
            assertThat(result.diagnostics().toString()).contains("INVALID_PERMISSION_MODE");
        }
    }

    private SettingsApplicationService service(HeadlessRuntimeSession runtime, Path home) {
        return SettingsApplicationService.production(runtime, home);
    }

    private SettingsApplicationService service(HeadlessRuntimeSession runtime, SettingsFixedSourceLoader loader,
                                               SettingsSnapshotStore store) {
        RuntimeSettingsApplier applier = new RuntimeSettingsApplier(runtime.runtimeConfiguration(),
                runtime.runtimeConfiguration().modelName().stream().toList(), runtime.builtinToolRegistry(), Map.of());
        return new SettingsApplicationService(runtime, loader, new SettingsResolver(), store, applier);
    }

    private SettingsFixedSourceLoader countingLoader(Path home, HeadlessRuntimeSession runtime, AtomicInteger calls) {
        SettingsV1SourceParser parser = new SettingsV1SourceParser(runtime.builtinToolRegistry().definitions().stream()
                .map(definition -> definition.name()).collect(java.util.stream.Collectors.toSet()));
        return new SettingsFixedSourceLoader(home, runtime.workspaceGuard(), parser) {
            @Override public SettingsSourceLoadResult loadUser(CancellationToken token) { calls.incrementAndGet(); return super.loadUser(token); }
            @Override public SettingsSourceLoadResult loadProjectShared(CancellationToken token) { calls.incrementAndGet(); return super.loadProjectShared(token); }
            @Override public SettingsSourceLoadResult loadProjectLocal(CancellationToken token) { calls.incrementAndGet(); return super.loadProjectLocal(token); }
        };
    }

    private SettingsFixedSourceLoader blockingLoader(Path home, HeadlessRuntimeSession runtime,
                                                     CountDownLatch firstEntered, CountDownLatch release,
                                                     AtomicInteger userLoadCalls, AtomicInteger activeLoads,
                                                     AtomicInteger maxConcurrentLoads) {
        SettingsV1SourceParser parser = new SettingsV1SourceParser(runtime.builtinToolRegistry().definitions().stream()
                .map(definition -> definition.name()).collect(java.util.stream.Collectors.toSet()));
        return new SettingsFixedSourceLoader(home, runtime.workspaceGuard(), parser) {
            @Override
            public SettingsSourceLoadResult loadUser(CancellationToken token) {
                userLoadCalls.incrementAndGet();
                int active = activeLoads.incrementAndGet();
                maxConcurrentLoads.accumulateAndGet(active, Math::max);
                firstEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                    return super.loadUser(token);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } finally {
                    activeLoads.decrementAndGet();
                }
            }
        };
    }

    private HeadlessRuntimeSession runtime(Path workspace) {
        return new HeadlessRuntimeSession(request -> ModelTurn.text("done"), AgentEventSink.noop(), options(workspace));
    }

    private HeadlessRuntimeOptions options(Path workspace) {
        return new HeadlessRuntimeOptions(workspace, "fake-model", Duration.ofSeconds(5), PermissionMode.DEFAULT,
                List.of(), io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest.create(), root.resolve("sessions"));
    }

    private static DeclaredSettings declaredMode(String mode) {
        return new DeclaredSettings(Optional.empty(), Optional.of(mode), List.of(), Optional.empty(), Map.of(), List.of(), Optional.empty());
    }
}
