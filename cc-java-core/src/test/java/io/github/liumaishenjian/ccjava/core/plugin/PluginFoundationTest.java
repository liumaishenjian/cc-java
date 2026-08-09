package io.github.liumaishenjian.ccjava.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.ToolExecutionOutcome;
import io.github.liumaishenjian.ccjava.core.ToolInvocation;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginFingerprint;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginManifest;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginNamespace;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginRegistryState;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginFoundationTest {

    @Test
    void manifestEnforcesNamespaceReferencesAndComponentCeiling() {
        var server = component(PluginComponentKind.MCP_SERVER, "primary", "mcp/primary.json");
        var provider = new PluginComponentDescriptor(
                PluginComponentKind.TOOL_PROVIDER, "remote", "providers/remote.json",
                "mcp-backed", List.of("primary"), "a".repeat(64));
        var manifest = new PluginManifest(1, new PluginId("alpha"), "1", null, null,
                List.of(server, provider));

        assertThat(manifest.components()).containsExactly(server, provider);
        assertThat(PluginNamespace.qualifiedTool(
                PluginNamespace.qualified(manifest.id(), PluginComponentKind.TOOL_PROVIDER, "remote"), "search"))
                .isEqualTo("plugin__alpha__tool-provider__remote__search");
        assertThatThrownBy(() -> new PluginManifest(1, new PluginId("alpha"), "1", null, null,
                List.of(new PluginComponentDescriptor(
                        PluginComponentKind.TOOL_PROVIDER, "remote", "provider.json", "mcp-backed",
                        List.of("missing"), "b".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> component(PluginComponentKind.SKILLS, "evil", "lib/evil.jar"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> component(PluginComponentKind.HOOKS, "script", "hooks/run.ps1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginManifest(1, new PluginId("alpha"), "1", null, null,
                java.util.stream.IntStream.range(0, 129)
                        .mapToObj(index -> component(PluginComponentKind.SKILLS,
                                "skill-" + index, "skills/" + index + ".md"))
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryFreezesSnapshotAndQuiescesWithoutKillingLease() {
        PluginSnapshot snapshot = snapshot("alpha", "a");
        var registry = new InMemoryPluginRegistry(fingerprint -> fingerprint.equals(snapshot.fingerprint()));
        registry.activate(snapshot);
        PluginLease lease = registry.acquire(new PluginId("alpha")).orElseThrow();

        assertThat(registry.activeSnapshot().snapshots()).containsExactly(snapshot);
        assertThat(registry.beginQuiescing(new PluginId("alpha"))).isTrue();
        assertThat(registry.acquire(new PluginId("alpha"))).isEmpty();
        assertThat(lease.snapshot()).isEqualTo(snapshot);
        assertThat(registry.completeRemoval(new PluginId("alpha"))).isEmpty();

        lease.close();
        lease.close();
        assertThat(registry.completeRemoval(new PluginId("alpha"))).contains(snapshot);
        assertThat(registry.state(new PluginId("alpha"))).contains(PluginRegistryState.REMOVED);
        registry.markDeleted(new PluginId("alpha"));
        assertThat(registry.state(new PluginId("alpha"))).isEmpty();
    }

    @Test
    void activatingNewVersionDoesNotDriftExistingSessionLease() {
        PluginSnapshot first = snapshot("alpha", "a");
        PluginSnapshot second = snapshot("alpha", "b");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(first);
        PluginLease oldSession = registry.acquire(first.manifest().id()).orElseThrow();

        registry.activate(second);
        PluginLease newSession = registry.acquire(second.manifest().id()).orElseThrow();

        assertThat(oldSession.snapshot()).isEqualTo(first);
        assertThat(newSession.snapshot()).isEqualTo(second);
        assertThat(registry.activeSnapshot().snapshots()).containsExactly(second);
        assertThat(registry.drainRetiredReady()).isEmpty();
        oldSession.close();
        assertThat(registry.leaseCount(second.manifest().id())).isEqualTo(1);
        var ready = registry.drainRetiredReady();
        assertThat(ready).singleElement().satisfies(generation ->
                assertThat(generation.snapshot()).isEqualTo(first));
        assertThat(registry.drainRetiredReady()).isEmpty();
        newSession.close();
    }

    @Test
    void preparedActivationCanRollbackCommittedGenerationBeforeExposure() {
        PluginSnapshot first = snapshot("alpha", "a");
        PluginSnapshot second = snapshot("alpha", "b");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(first);

        PluginActivation activation = registry.prepareActivation(second);
        activation.commit();
        assertThat(registry.activeSnapshot().snapshots()).containsExactly(second);
        activation.rollback();

        assertThat(registry.activeSnapshot().snapshots()).containsExactly(first);
        assertThat(registry.drainRetiredReady()).isEmpty();
    }

    @Test
    void registryRejectsUntrustedAndTombstoneCannotReactivate() {
        PluginSnapshot snapshot = snapshot("alpha", "a");
        assertThatThrownBy(() -> new InMemoryPluginRegistry(fingerprint -> false).activate(snapshot))
                .isInstanceOf(IllegalArgumentException.class);

        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(snapshot);
        registry.beginQuiescing(snapshot.manifest().id());
        registry.completeRemoval(snapshot.manifest().id());
        registry.markTombstoned(snapshot.manifest().id());
        assertThat(registry.state(snapshot.manifest().id())).contains(PluginRegistryState.TOMBSTONED);
        assertThatThrownBy(() -> registry.activate(snapshot)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runCoordinatorCapturesGenerationAndReleasesEveryTerminalPathExactlyOnce() {
        PluginSnapshot snapshot = snapshot("alpha", "a");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        registry.activate(snapshot);
        var coordinator = new PluginRunCoordinator(registry);
        var first = new io.github.liumaishenjian.ccjava.domain.RunId("run-success");
        var second = new io.github.liumaishenjian.ccjava.domain.RunId("run-cancelled");

        coordinator.openRun(first);
        coordinator.openRun(second);
        assertThat(registry.leaseCount(snapshot.manifest().id())).isEqualTo(2);
        assertThat(coordinator.fingerprints(first)).containsEntry(
                snapshot.manifest().id(), snapshot.fingerprint().treeDigest());
        assertThat(registry.beginQuiescing(snapshot.manifest().id())).isTrue();
        var afterQuiesce = new io.github.liumaishenjian.ccjava.domain.RunId("run-after-quiesce");
        coordinator.openRun(afterQuiesce);
        assertThat(coordinator.fingerprints(afterQuiesce)).isEmpty();
        coordinator.closeRun(afterQuiesce);

        coordinator.closeRun(second);
        coordinator.closeRun(second);
        coordinator.closeRun(first);
        assertThat(registry.leaseCount(snapshot.manifest().id())).isZero();
        assertThat(registry.completeRemoval(snapshot.manifest().id())).contains(snapshot);
    }

    @Test
    void contributionClosesResourcesReverseThenLeaseExactlyOnce() throws Exception {
        var order = new ArrayList<String>();
        var leaseCloses = new AtomicInteger();
        PluginSnapshot snapshot = snapshot("alpha", "a");
        PluginLease lease = new PluginLease() {
            @Override public PluginSnapshot snapshot() { return snapshot; }
            @Override public void close() { leaseCloses.incrementAndGet(); order.add("lease"); }
        };
        AutoCloseable first = () -> order.add("first");
        AutoCloseable second = () -> order.add("second");
        var contribution = new PluginToolContribution(List.of(new StubTool()), List.of(first, second), lease);

        contribution.close();
        contribution.close();

        assertThat(order).containsExactly("second", "first", "lease");
        assertThat(leaseCloses).hasValue(1);
        assertThat(contribution.snapshot()).isEqualTo(snapshot);
    }

    @Test
    void providerRegistryRejectsDynamicOrDuplicateTypes() {
        PluginToolProviderFactory first = factory("mcp-backed");
        assertThat(new PluginToolProviderFactories(List.of(first)).find("mcp-backed")).contains(first);
        assertThatThrownBy(() -> new PluginToolProviderFactories(List.of(first, factory("mcp-backed"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginToolProviderFactories(List.of(factory("class:evil.Main"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PluginToolProviderFactory factory(String type) {
        return new PluginToolProviderFactory() {
            @Override public String providerType() { return type; }
            @Override public PluginToolContribution create(
                    PluginToolProviderDescriptor descriptor, PluginLease lease) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static PluginComponentDescriptor component(PluginComponentKind kind, String name, String path) {
        return new PluginComponentDescriptor(kind, name, path, null, List.of(), null);
    }

    private static PluginSnapshot snapshot(String id, String digestCharacter) {
        var pluginId = new PluginId(id);
        var manifest = new PluginManifest(1, pluginId, "1", null, null,
                List.of(component(PluginComponentKind.SKILLS, "skill", "skills/SKILL.md")));
        String digest = digestCharacter.repeat(64);
        return new PluginSnapshot(manifest,
                new PluginFingerprint(pluginId, "1", digest, digest), digest.substring(0, 32));
    }

    private static final class StubTool implements AgentTool {
        @Override public ToolDefinition definition() {
            return ToolDefinition.readOnlyText("stub", "stub", "{}");
        }
        @Override public ToolExecutionOutcome execute(ToolInvocation invocation) {
            return ToolExecutionOutcome.success("ok");
        }
    }
}
