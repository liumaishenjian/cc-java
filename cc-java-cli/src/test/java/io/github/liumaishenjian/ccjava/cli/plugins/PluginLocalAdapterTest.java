package io.github.liumaishenjian.ccjava.cli.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.plugin.InMemoryPluginRegistry;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginRegistryState;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import io.github.liumaishenjian.ccjava.cli.skills.PluginSkillScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLocalAdapterTest {
    @TempDir Path temporary;

    @Test
    void strictManifestAcceptsV1AndRejectsUnknownDuplicateTypeDangerousAndUndeclared() {
        var parser = new PluginManifestParser();
        var parsed = parser.parse(manifest("alpha"));
        assertThat(parsed.manifest().id()).isEqualTo(new PluginId("alpha"));
        assertThat(parsed.manifest().components()).hasSize(2);

        assertCode(() -> parser.parse(new String(manifest("alpha"), StandardCharsets.UTF_8).replace(
                "\"version\":\"1\"", "\"version\":\"1\",\"evil\":true").getBytes(StandardCharsets.UTF_8)),
                PluginErrorCode.MANIFEST_INVALID);
        assertCode(() -> parser.parse(new String(manifest("alpha"), StandardCharsets.UTF_8).replace(
                "\"id\":\"alpha\"", "\"id\":\"alpha\",\"id\":\"beta\"")
                .getBytes(StandardCharsets.UTF_8)), PluginErrorCode.MANIFEST_INVALID);
        assertCode(() -> parser.parse(new String(manifest("alpha"), StandardCharsets.UTF_8).replace(
                "\"version\":\"1\"", "\"version\":1").getBytes(StandardCharsets.UTF_8)),
                PluginErrorCode.MANIFEST_INVALID);
        assertCode(() -> parser.parse(new String(manifest("alpha"), StandardCharsets.UTF_8).replace(
                "\"type\":\"mcp-backed\"", "\"type\":\"mcp-backed\",\"class\":\"Evil\"")
                .getBytes(StandardCharsets.UTF_8)), PluginErrorCode.MANIFEST_INVALID);
        assertCode(() -> parser.parse(new String(manifest("alpha"), StandardCharsets.UTF_8).replace(
                "\"mcpServers\":[\"primary\"]", "\"mcpServers\":[\"missing\"]")
                .getBytes(StandardCharsets.UTF_8)), PluginErrorCode.MANIFEST_INVALID);
    }

    @Test
    void manifestExactSizeAndComponentCeilings() {
        var parser = new PluginManifestParser();
        byte[] exact = manifestWithPadding(PluginManifestParser.MAX_BYTES);
        assertThat(exact).hasSize(PluginManifestParser.MAX_BYTES);
        assertThat(parser.parse(exact).manifest().id().value()).isEqualTo("alpha");
        assertCode(() -> parser.parse(Arrays.copyOf(exact, exact.length + 1)),
                PluginErrorCode.MANIFEST_TOO_LARGE);

        assertThat(parser.parse(componentManifest(128)).manifest().components()).hasSize(128);
        assertCode(() -> parser.parse(componentManifest(129)), PluginErrorCode.COMPONENT_LIMIT_EXCEEDED);
    }

    @Test
    void canonicalTreeIsStableOrderIndependentAndChangesForAnyByte() throws Exception {
        Path first = pluginDirectory("alpha");
        Files.writeString(first.resolve("payload.txt"), "one", StandardCharsets.UTF_8);
        var loader = new PluginPackageLoader();
        var original = loader.load(first);
        var repeated = loader.load(first);
        assertThat(repeated.fingerprint()).isEqualTo(original.fingerprint());

        Files.writeString(first.resolve("payload.txt"), "two", StandardCharsets.UTF_8);
        var changed = loader.load(first);
        assertThat(changed.fingerprint().treeDigest()).isNotEqualTo(original.fingerprint().treeDigest());
        assertThat(changed.fingerprint().manifestDigest()).isEqualTo(original.fingerprint().manifestDigest());
    }

    @Test
    void canonicalTreeAcceptsExactFileAndAggregateCeilingsAndRejectsPlusOne() throws Exception {
        Path files = pluginDirectory("files");
        for (int index = 0; index < CanonicalPluginTree.MAX_FILES - 3; index++) {
            Files.write(files.resolve("f" + index), new byte[0]);
        }
        assertThat(new PluginPackageLoader().load(files).manifest().id().value()).isEqualTo("files");
        Files.write(files.resolve("overflow"), new byte[0]);
        assertCode(() -> new PluginPackageLoader().load(files), PluginErrorCode.TREE_FILE_LIMIT_EXCEEDED);

        Path exact = pluginDirectory("exact");
        long manifestBytes = Files.size(exact.resolve("plugin.json"))
                + Files.size(exact.resolve("mcp/primary.json"))
                + Files.size(exact.resolve("providers/remote.json"));
        try (var channel = java.nio.channels.FileChannel.open(exact.resolve("payload.bin"),
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(CanonicalPluginTree.MAX_TOTAL_BYTES - manifestBytes - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        assertThat(new PluginPackageLoader().load(exact).manifest().id().value()).isEqualTo("exact");
        Files.write(exact.resolve("plus-one"), new byte[] {1});
        assertCode(() -> new PluginPackageLoader().load(exact), PluginErrorCode.TREE_SIZE_LIMIT_EXCEEDED);
    }

    @Test
    void canonicalTreeRejectsSymlinkAndDeterministicReparseSeam() throws Exception {
        Path root = pluginDirectory("alpha");
        Path external = temporary.resolve("external.txt");
        Files.writeString(external, "secret");
        try {
            Files.createSymbolicLink(root.resolve("linked"), external);
            assertCode(() -> new PluginPackageLoader().load(root),
                    PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 当前平台不允许测试进程创建 link；deterministic seam 仍覆盖相同拒绝分支。
        }

        var tree = new CanonicalPluginTree(path -> path.getFileName().toString().equals("plugin.json"));
        var parsed = new PluginManifestParser().parse(Files.readAllBytes(root.resolve("plugin.json")));
        assertCode(() -> tree.scan(root, parsed), PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
    }

    @Test
    void archiveInputAndAllFaultPointsFailClosedWithoutActivation() throws Exception {
        Path archive = temporary.resolve("plugin.zip");
        Files.write(archive, new byte[] {1});
        assertCode(() -> new PluginPackageLoader().load(archive), PluginErrorCode.ARCHIVE_REJECTED);

        Path source = pluginDirectory("alpha");
        for (DirectoryPluginInstaller.FaultPoint point : DirectoryPluginInstaller.FaultPoint.values()) {
            Path store = temporary.resolve("store-" + point.name());
            var registry = new InMemoryPluginRegistry(fingerprint -> true);
            var installer = new DirectoryPluginInstaller(store, new PluginPackageLoader(), registry,
                    current -> { if (current == point) throw new IOException("sentinel-path-secret"); },
                    ignored -> { });
            assertCode(() -> installer.install(source), PluginErrorCode.INSTALL_FAILED);
            assertThat(registry.activeSnapshot().snapshots()).isEmpty();
            if (Files.exists(store)) {
                try (var paths = Files.list(store)) {
                    assertThat(paths.map(path -> path.getFileName().toString()).toList())
                            .allMatch(name -> name.equals("registry.v1"));
                }
            }
        }
    }

    @Test
    void registryReplaceThenActivationFailureRestoresOldIndexSnapshotLeaseAndDirectory() throws Exception {
        Path oldSource = pluginDirectory("alpha");
        Path store = temporary.resolve("store-rollback");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var oldSnapshot = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { }).install(oldSource);
        var oldLease = registry.acquire(oldSnapshot.manifest().id()).orElseThrow();
        byte[] oldIndex = Files.readAllBytes(store.resolve("registry.v1"));
        Path oldDirectory = store.resolve(PluginRegistryIndex.directoryName(
                "alpha", oldSnapshot.fingerprint().treeDigest()));

        Path newSource = pluginDirectory("alpha");
        Files.writeString(newSource.resolve("payload.txt"), "new generation", StandardCharsets.UTF_8);
        var failing = new DirectoryPluginInstaller(store, new PluginPackageLoader(), registry,
                point -> { if (point == DirectoryPluginInstaller.FaultPoint.AFTER_ACTIVATION_COMMIT) {
                    throw new IOException("activation-fault");
                } }, ignored -> { });

        assertCode(() -> failing.install(newSource), PluginErrorCode.INSTALL_FAILED);
        assertThat(Files.readAllBytes(store.resolve("registry.v1"))).isEqualTo(oldIndex);
        assertThat(Files.isDirectory(oldDirectory)).isTrue();
        assertThat(registry.activeSnapshot().snapshots()).containsExactly(oldSnapshot);
        assertThat(oldLease.snapshot()).isEqualTo(oldSnapshot);
        oldLease.close();
    }

    @Test
    void deterministicReparseSeamNeverDeletesExternalContent() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("delete-root/junction"));
        Path external = temporary.resolve("outside.txt");
        Files.writeString(external, "keep", StandardCharsets.UTF_8);
        var deleted = new java.util.ArrayList<Path>();
        var trees = new SafePluginTreeOperator(
                path -> path.getFileName().toString().equals("junction"), deleted::add);

        assertThatThrownBy(() -> trees.delete(root.getParent()))
                .isInstanceOf(PluginBoundaryException.class);
        assertThat(deleted).isEmpty();
        assertThat(Files.readString(external)).isEqualTo("keep");
    }

    @Test
    void unsupportedDirectoryFlushFailsClosed() throws Exception {
        Path source = pluginDirectory("flush-fail");
        Path store = temporary.resolve("store-flush-fail");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(store, new PluginPackageLoader(), registry,
                ignored -> { }, ignored -> { throw new IOException("unsupported"); });

        assertCode(() -> installer.install(source), PluginErrorCode.INSTALL_FAILED);
        assertThat(registry.activeSnapshot().snapshots()).isEmpty();
    }

    @Test
    void productionResourcesLoadOnlyExactTrustedDirectoryAndCloseRemoteContribution() throws Exception {
        io.github.liumaishenjian.ccjava.mcp.McpServerConfig config = new io.github.liumaishenjian.ccjava.mcp.McpServerConfig(
                "primary", new io.github.liumaishenjian.ccjava.mcp.McpTransportConfig.Stdio(
                        temporary.resolve("fake.exe").toAbsolutePath(), java.util.List.of(), java.util.List.of()),
                java.util.List.of(), java.util.List.of(),
                java.time.Duration.ofSeconds(1), true);
        Path source = Files.createDirectory(temporary.resolve("runtime-source"));
        String digest = io.github.liumaishenjian.ccjava.mcp.McpPluginConfigDigest.compute(java.util.List.of(config));
        Files.writeString(source.resolve("plugin.json"),
                new String(manifest("runtime"), StandardCharsets.UTF_8).replace("a".repeat(64), digest));
        Files.createDirectories(source.resolve("mcp"));
        Files.createDirectories(source.resolve("providers"));
        Files.writeString(source.resolve("mcp/primary.json"), "{}");
        Files.writeString(source.resolve("providers/remote.json"), "{}");
        Path store = temporary.resolve("runtime-store");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var snapshot = new DirectoryPluginInstaller(store, new PluginPackageLoader(), registry,
                ignored -> { }, ignored -> { }).install(source);
        Files.writeString(store.resolve("plugin-trust.v1"), snapshot.manifest().id().value() + "\t"
                + snapshot.manifest().version() + "\t" + snapshot.fingerprint().treeDigest() + "\t"
                + snapshot.fingerprint().manifestDigest() + "\n", StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        io.github.liumaishenjian.ccjava.mcp.McpRemoteClient client = new io.github.liumaishenjian.ccjava.mcp.McpRemoteClient() {
            @Override public void initialize() { }
            @Override public java.util.List<io.github.liumaishenjian.ccjava.mcp.McpToolDescriptor> listTools() {
                return java.util.List.of(new io.github.liumaishenjian.ccjava.mcp.McpToolDescriptor(
                        "search", "search", java.util.Map.of("type", "object")));
            }
            @Override public io.github.liumaishenjian.ccjava.mcp.McpCallOutcome callTool(
                    String name, java.util.Map<String, Object> arguments) {
                calls.incrementAndGet();
                return new io.github.liumaishenjian.ccjava.mcp.McpCallOutcome(false, "ok");
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        PluginRuntimeResources resources = PluginRuntimeResources.load(store, java.util.List.of(config), ignored -> client);
        assertThat(resources.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.definition().source()).isEqualTo(io.github.liumaishenjian.ccjava.domain.ToolSource.PLUGIN);
            assertThat(tool.execute(new io.github.liumaishenjian.ccjava.core.ToolInvocation(
                    new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                    new io.github.liumaishenjian.ccjava.domain.RunId("run"), 1,
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("call", tool.definition().name(),
                            io.github.liumaishenjian.ccjava.domain.JsonObject.empty()))).successful()).isTrue();
        });
        assertThat(calls).hasValue(1);
        assertThat(resources.contributionLeaseCount()).isEqualTo(1);
        resources.close();
        resources.close();
        assertThat(closes).hasValue(1);
        assertThat(resources.contributionLeaseCount()).isZero();

        Files.writeString(store.resolve("plugin-trust.v1"), "runtime\t1\t" + "0".repeat(64) + "\t"
                + "0".repeat(64) + "\n");
        assertThat(PluginRuntimeResources.load(store, java.util.List.of(config), ignored -> client).tools()).isEmpty();
    }

    @Test
    void skillHookBinderRejectsUntrustedAndCrossPluginReferencesWithoutLease() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("hook-source"));
        Files.createDirectories(source.resolve("hooks"));
        Files.writeString(source.resolve("hooks/audit.json"),
                "{\"version\":1,\"id\":\"audit\",\"event\":\"POST_TOOL\",\"failurePolicy\":\"FAIL_OPEN\",\"timeoutMs\":1000,\"url\":\"http://127.0.0.1:9/hook\"}");
        Files.writeString(source.resolve("plugin.json"),
                "{\"schemaVersion\":1,\"id\":\"alpha\",\"version\":\"1\",\"components\":{"
                        + "\"hooks\":[{\"name\":\"audit\",\"path\":\"hooks/audit.json\"}]}}",
                StandardCharsets.UTF_8);
        var snapshot = new PluginPackageLoader().load(source);
        Path store = Files.createDirectories(temporary.resolve("hook-store"));
        Path installed = Files.createDirectory(store.resolve(PluginRegistryIndex.directoryName(
                "alpha", snapshot.fingerprint().treeDigest())));
        Files.createDirectories(installed.resolve("hooks"));
        Files.copy(source.resolve("plugin.json"), installed.resolve("plugin.json"));
        Files.copy(source.resolve("hooks/audit.json"), installed.resolve("hooks/audit.json"));
        var templates = new PluginRunHookTemplates(store, java.util.List.of(snapshot));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var coordinator = new io.github.liumaishenjian.ccjava.core.hook.HookCoordinator(
                    java.util.List.of(), executor, java.time.Duration.ofSeconds(1));
            var binder = templates.skillBinder(coordinator);
            var local = new SkillDescriptor(new SkillId("local"), "local", SkillInvocationPolicy.BOTH,
                    SkillSource.USER, "user/local", "a".repeat(64), SkillToolRestriction.unspecified(),
                    java.util.List.of(), java.util.List.of("audit"));
            var crossPlugin = new SkillDescriptor(new SkillId("plugin__beta__skills__review"), "cross",
                    SkillInvocationPolicy.BOTH, SkillSource.PLUGIN, "plugin/beta/review", "b".repeat(64),
                    SkillToolRestriction.unspecified(), java.util.List.of(), java.util.List.of("audit"));
            var runId = new io.github.liumaishenjian.ccjava.domain.RunId("run-hook-boundary");

            assertThatThrownBy(() -> binder.bind(runId, local)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> binder.bind(runId, crossPlugin)).isInstanceOf(IllegalArgumentException.class);
            assertThat(coordinator.runBindingCount(runId)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void registryPreservesTwoPluginsReplacesOneAndReloadsAfterUninstall() throws Exception {
        Path alphaSource = pluginDirectory("alpha");
        Path betaSource = pluginDirectory("beta");
        Path store = temporary.resolve("store-multiple");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { });
        var alpha = installer.install(alphaSource);
        var beta = installer.install(betaSource);

        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .extracting(PluginRegistryIndex.Entry::id)
                .containsExactly("alpha", "beta");

        Path replacement = pluginDirectory("alpha");
        Files.writeString(replacement.resolve("payload.txt"), "replacement", StandardCharsets.UTF_8);
        var updatedAlpha = installer.install(replacement);
        assertThat(updatedAlpha.fingerprint().treeDigest()).isNotEqualTo(alpha.fingerprint().treeDigest());
        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .containsExactly(PluginRegistryIndex.Entry.from(updatedAlpha), PluginRegistryIndex.Entry.from(beta));

        writeTrust(store, updatedAlpha, beta);
        try (PluginRuntimeResources loaded = PluginRuntimeResources.load(store, java.util.List.of(),
                ignored -> { throw new AssertionError("no MCP contribution expected"); })) {
            assertThat(loaded.skills().entries()).isEmpty();
        }

        assertThat(new DirectoryPluginUninstaller(store, registry, new SafePluginTreeOperator(), ignored -> { }).uninstall(beta.manifest().id()).removed()).isTrue();
        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .containsExactly(PluginRegistryIndex.Entry.from(updatedAlpha));
        writeTrust(store, updatedAlpha);
        try (PluginRuntimeResources loaded = PluginRuntimeResources.load(store, java.util.List.of(),
                ignored -> { throw new AssertionError("no MCP contribution expected"); })) {
            assertThat(loaded.skills().entries()).isEmpty();
        }
    }

    @Test
    void uninstallDeleteFailureRestoresOldIndexAndPreservesUnrelatedPlugin() throws Exception {
        Path store = temporary.resolve("store-delete-failure");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { });
        var alpha = installer.install(pluginDirectory("alpha"));
        var beta = installer.install(pluginDirectory("beta"));
        byte[] oldIndex = Files.readAllBytes(store.resolve("registry.v1"));
        Path alphaDirectory = store.resolve(PluginRegistryIndex.directoryName(
                "alpha", alpha.fingerprint().treeDigest()));
        var uninstaller = new DirectoryPluginUninstaller(store, registry, new SafePluginTreeOperator(),
                ignored -> { }, point -> {
                    if (point == DirectoryPluginUninstaller.FaultPoint.BEFORE_DIRECTORY_DELETE) {
                        throw new IOException("delete-failure");
                    }
                });

        var result = uninstaller.uninstall(alpha.manifest().id());
        assertThat(result.errorCode()).isEqualTo(PluginErrorCode.UNINSTALL_TOMBSTONED);
        assertThat(Files.readAllBytes(store.resolve("registry.v1"))).isEqualTo(oldIndex);
        assertThat(alphaDirectory).isDirectory();
        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .containsExactly(PluginRegistryIndex.Entry.from(alpha), PluginRegistryIndex.Entry.from(beta));
    }

    @Test
    void uninstallFailureAfterDirectoryDeleteKeepsNewIndexWithoutDanglingTarget() throws Exception {
        Path store = temporary.resolve("store-post-delete-failure");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { });
        var alpha = installer.install(pluginDirectory("alpha"));
        var beta = installer.install(pluginDirectory("beta"));
        Path alphaDirectory = store.resolve(PluginRegistryIndex.directoryName(
                "alpha", alpha.fingerprint().treeDigest()));
        var uninstaller = new DirectoryPluginUninstaller(store, registry, new SafePluginTreeOperator(),
                ignored -> { }, point -> {
                    if (point == DirectoryPluginUninstaller.FaultPoint.AFTER_DIRECTORY_DELETE) {
                        throw new IOException("post-delete-failure");
                    }
                });

        var result = uninstaller.uninstall(alpha.manifest().id());
        assertThat(result.errorCode()).isEqualTo(PluginErrorCode.UNINSTALL_TOMBSTONED);
        assertThat(alphaDirectory).doesNotExist();
        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .containsExactly(PluginRegistryIndex.Entry.from(beta));
    }

    @Test
    void uninstallBackupCleanupFailureKeepsPublishedIndexAndUnrelatedPlugin() throws Exception {
        Path store = temporary.resolve("store-backup-cleanup-failure");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { });
        var alpha = installer.install(pluginDirectory("alpha"));
        var beta = installer.install(pluginDirectory("beta"));
        Path alphaDirectory = store.resolve(PluginRegistryIndex.directoryName(
                "alpha", alpha.fingerprint().treeDigest()));
        var uninstaller = new DirectoryPluginUninstaller(store, registry, new SafePluginTreeOperator(),
                ignored -> { }, point -> {
                    if (point == DirectoryPluginUninstaller.FaultPoint.BEFORE_BACKUP_DELETE) {
                        throw new IOException("backup-cleanup-failure");
                    }
                });

        var result = uninstaller.uninstall(alpha.manifest().id());
        assertThat(result.errorCode()).isEqualTo(PluginErrorCode.UNINSTALL_TOMBSTONED);
        assertThat(alphaDirectory).doesNotExist();
        assertThat(PluginRegistryIndex.read(store.resolve("registry.v1")))
                .containsExactly(PluginRegistryIndex.Entry.from(beta));
        assertThat(registry.state(alpha.manifest().id())).isEmpty();
    }

    @Test
    void exactContentAddressedDirectoryAllowsPrefixPluginIdsToCoexist() throws Exception {
        Path fooSource = pluginDirectory("foo");
        Path extraSource = pluginDirectory("foo-extra");
        Path store = temporary.resolve("store-prefix");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installer = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { });
        var foo = installer.install(fooSource);
        var extra = installer.install(extraSource);
        writeTrust(store, foo, extra);

        assertThat(store.resolve(PluginRegistryIndex.directoryName(
                "foo", foo.fingerprint().treeDigest()))).isDirectory();
        assertThat(store.resolve(PluginRegistryIndex.directoryName(
                "foo-extra", extra.fingerprint().treeDigest()))).isDirectory();
        try (PluginRuntimeResources loaded = PluginRuntimeResources.load(store, java.util.List.of(),
                ignored -> { throw new AssertionError("no MCP contribution expected"); })) {
            assertThat(loaded.skills().entries()).isEmpty();
        }
    }

    @Test
    void beforeStagingCreateFaultRunsBeforeDirectoryExists() throws Exception {
        Path source = pluginDirectory("fault-order");
        Path store = temporary.resolve("store-fault-order");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var sawMissing = new java.util.concurrent.atomic.AtomicBoolean();
        var installer = new DirectoryPluginInstaller(store, new PluginPackageLoader(), registry, point -> {
            if (point == DirectoryPluginInstaller.FaultPoint.BEFORE_STAGING_CREATE) {
                try (var paths = Files.list(store)) {
                    sawMissing.set(paths.noneMatch(path -> path.getFileName().toString().startsWith(".staging-")));
                }
                throw new IOException("fault");
            }
        }, ignored -> { });

        assertCode(() -> installer.install(source), PluginErrorCode.INSTALL_FAILED);
        assertThat(sawMissing).isTrue();
    }

    @Test
    void pluginSkillScannerRejectsInvalidUtf8LineLimitResourceAndTraversal() throws Exception {
        Path store = temporary.resolve("skill-boundaries");

        var malformedSkill = installedSkillPlugin(store, "bad-skill", "skills/review/SKILL.md",
                validSkillBytes("resources:\n"));
        Files.write(malformedSkill.directory().resolve("skills/review/SKILL.md"), new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> new PluginSkillScanner().scan(store, java.util.List.of(malformedSkill.snapshot())))
                .isInstanceOf(IllegalArgumentException.class);

        var tooManyLines = installedSkillPlugin(store, "too-many-lines", "skills/review/SKILL.md",
                ("---\nname: review\ndescription: review\n---\n" + "x\n".repeat(4_000))
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new PluginSkillScanner().scan(store, java.util.List.of(tooManyLines.snapshot())))
                .isInstanceOf(IllegalArgumentException.class);

        var malformedResource = installedSkillPlugin(store, "bad-resource", "skills/review/SKILL.md",
                validSkillBytes("resources:\n  - bad.txt\n"));
        Files.write(malformedResource.directory().resolve("skills/review/bad.txt"),
                new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> new PluginSkillScanner().scan(store,
                java.util.List.of(malformedResource.snapshot()))).isInstanceOf(IllegalArgumentException.class);

        var traversal = installedSkillPlugin(store, "traversal", "skills/review/SKILL.md",
                validSkillBytes("resources:\n  - ../../outside.txt\n"));
        Files.writeString(traversal.directory().resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new PluginSkillScanner().scan(store, java.util.List.of(traversal.snapshot())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installThenQuiescingUninstallWaitsForLease() throws Exception {
        Path source = pluginDirectory("alpha");
        Path store = temporary.resolve("store-success");
        var registry = new InMemoryPluginRegistry(fingerprint -> true);
        var installed = new DirectoryPluginInstaller(
                store, new PluginPackageLoader(), registry, ignored -> { }, ignored -> { }).install(source);
        var lease = registry.acquire(installed.manifest().id()).orElseThrow();
        var uninstaller = new DirectoryPluginUninstaller(store, registry, new SafePluginTreeOperator(), ignored -> { });

        var deferred = uninstaller.uninstall(installed.manifest().id());
        assertThat(deferred.errorCode()).isEqualTo(PluginErrorCode.UNINSTALL_DEFERRED);
        assertThat(registry.state(installed.manifest().id())).contains(PluginRegistryState.QUIESCING);
        assertThat(lease.snapshot()).isEqualTo(installed);
        lease.close();
        assertThat(uninstaller.finish(installed.manifest().id()).removed()).isTrue();
        assertThat(registry.state(installed.manifest().id())).isEmpty();
    }

    private InstalledSkillPlugin installedSkillPlugin(Path store, String id, String componentPath, byte[] skillBytes)
            throws IOException {
        Path source = Files.createDirectory(temporary.resolve("skill-source-" + id));
        Path skillFile = source.resolve(componentPath);
        Files.createDirectories(skillFile.getParent());
        Files.write(skillFile, skillBytes);
        Files.writeString(source.resolve("plugin.json"),
                "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"components\":{"
                        + "\"skills\":[{\"name\":\"review\",\"path\":\"" + componentPath + "\"}]}}",
                StandardCharsets.UTF_8);
        var snapshot = new PluginPackageLoader().load(source);
        Files.createDirectories(store);
        Path installed = Files.createDirectory(store.resolve(PluginRegistryIndex.directoryName(
                id, snapshot.fingerprint().treeDigest())));
        copyDirectory(source, installed);
        return new InstalledSkillPlugin(snapshot, installed);
    }

    private static byte[] validSkillBytes(String extraMetadata) {
        return ("---\nname: review\ndescription: review\n" + extraMetadata + "---\nbody\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) continue;
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private record InstalledSkillPlugin(io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot snapshot,
            Path directory) { }

    private static void writeTrust(Path store, io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot... snapshots)
            throws IOException {
        StringBuilder content = new StringBuilder();
        for (var snapshot : snapshots) {
            content.append(snapshot.manifest().id().value()).append('\t')
                    .append(snapshot.manifest().version()).append('\t')
                    .append(snapshot.fingerprint().treeDigest()).append('\t')
                    .append(snapshot.fingerprint().manifestDigest()).append('\n');
        }
        Files.writeString(store.resolve("plugin-trust.v1"), content.toString(), StandardCharsets.UTF_8);
    }

    private Path pluginDirectory(String id) throws IOException {
        Path root = Files.createDirectory(temporary.resolve(id + "-" + System.nanoTime()));
        Files.write(root.resolve("plugin.json"), manifest(id));
        Path mcp = Files.createDirectories(root.resolve("mcp"));
        Path providers = Files.createDirectories(root.resolve("providers"));
        Files.writeString(mcp.resolve("primary.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(providers.resolve("remote.json"), "{}", StandardCharsets.UTF_8);
        return root;
    }

    private static byte[] manifest(String id) {
        return ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"components\":{"
                + "\"mcpServers\":[{\"name\":\"primary\",\"path\":\"mcp/primary.json\"}],"
                + "\"toolProviders\":[{\"name\":\"remote\",\"path\":\"providers/remote.json\","
                + "\"type\":\"mcp-backed\",\"mcpServers\":[\"primary\"],\"configDigest\":\""
                + "a".repeat(64) + "\"}]}}" ).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] manifestWithPadding(int targetBytes) {
        byte[] base = manifest("alpha");
        byte[] bytes = Arrays.copyOf(base, targetBytes);
        Arrays.fill(bytes, base.length, targetBytes, (byte) ' ');
        return bytes;
    }

    private static byte[] componentManifest(int count) {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"id\":\"alpha\",\"version\":\"1\",\"components\":{\"skills\":[");
        for (int index = 0; index < count; index++) {
            if (index != 0) json.append(',');
            json.append("{\"name\":\"skill-").append(index).append("\",\"path\":\"skills/")
                    .append(index).append(".md\"}");
        }
        return json.append("]}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCode(Runnable operation, PluginErrorCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(PluginBoundaryException.class)
                .extracting(failure -> ((PluginBoundaryException) failure).code())
                .isEqualTo(code);
    }
}
