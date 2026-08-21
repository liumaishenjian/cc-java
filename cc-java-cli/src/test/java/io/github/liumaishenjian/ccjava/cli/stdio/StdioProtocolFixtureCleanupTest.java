package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioProtocolFixtureCleanupTest {
    @TempDir Path temporary;

    @Test
    void closesPlanAndProviderHandlersWithoutTemporaryRootResidue() throws Exception {
        Path systemTemporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        java.util.Set<String> beforePlan = fixtureDirectories(systemTemporary, "cc-java-plan-provider-fixture-");
        StdioProtocol.CommandHandler plan = StdioProtocolFixtureMain.planRuntimeHandlerForTest(temporary);
        plan.close();
        assertThat(fixtureDirectories(systemTemporary, "cc-java-plan-provider-fixture-"))
                .containsExactlyInAnyOrderElementsOf(beforePlan);

        java.util.Set<String> beforeProvider = fixtureDirectories(
                systemTemporary, "cc-java-provider-control-fixture-");
        StdioProtocol.CommandHandler provider = StdioProtocolFixtureMain.providerControlHandlerForTest();
        provider.close();
        assertThat(fixtureDirectories(systemTemporary, "cc-java-provider-control-fixture-"))
                .containsExactlyInAnyOrderElementsOf(beforeProvider);
    }

    @Test
    void deletesOnlyNamedStrictDescendantAndRejectsSibling() throws Exception {
        Path parent = temporary.toAbsolutePath().normalize();
        Path realParent = parent.toRealPath();
        Path fixture = Files.createTempDirectory(parent, "permission-runtime-");
        Files.writeString(fixture.resolve("value.txt"), "fixture");
        StdioProtocolFixtureMain.deleteFixtureTree(
                parent, realParent, fixture, "permission-runtime-");
        assertThat(fixture).doesNotExist();

        Path sibling = Files.createTempDirectory(parent.getParent(), "permission-runtime-outside-");
        try {
            assertThatThrownBy(() -> StdioProtocolFixtureMain.deleteFixtureTree(
                    parent, realParent, sibling, "permission-runtime-"))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(sibling).exists();
        } finally {
            Files.delete(sibling);
        }
    }

    private static java.util.Set<String> fixtureDirectories(Path parent, String prefix) throws Exception {
        try (var children = Files.list(parent)) {
            return children.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
