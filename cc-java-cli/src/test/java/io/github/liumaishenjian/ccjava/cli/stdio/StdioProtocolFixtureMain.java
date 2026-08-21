package io.github.liumaishenjian.ccjava.cli.stdio;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 供跨进程测试启动的 Fake stdio 进程，不进入生产制品。
 */
public final class StdioProtocolFixtureMain {

    private StdioProtocolFixtureMain() {
    }

    /**
     * 启动确定性 Fake Server。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        boolean failed = false;
        try {
            StdioProtocol.CommandHandler handler = args.length == 1 && args[0].equals("provider-control")
                    ? providerControlHandler()
                    : args.length == 2 && args[0].equals("permission-runtime")
                            ? permissionRuntimeHandler(Path.of(args[1]))
                    : args.length == 2 && args[0].equals("plan-runtime")
                            ? planRuntimeHandler(Path.of(args[1]))
                            : new FakeStdioCommandHandler(List.of("alpha ", "beta"), Duration.ofMillis(250));
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(System.in, System.out, handler).run();
            failed = reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR;
        } catch (Exception exception) {
            failed = true;
        }
        if (failed) System.exit(2);
    }

    private static StdioProtocol.CommandHandler planRuntimeHandler(Path workspace) throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger executionCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean directExecution = new java.util.concurrent.atomic.AtomicBoolean();
        String markdown = "# Cross-process plan\n\n1. Inspect workspace safely.\n";
        String revisedMarkdown = "# Cross-process plan\n\n1. Inspect workspace safely.\n2. Verify rollback behavior.\n";
        String digest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(markdown);
        String revisedDigest = io.github.liumaishenjian.ccjava.domain.PlanArtifact.digest(revisedMarkdown);
        io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> {
            boolean executing = request.messages().stream()
                    .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                    .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                    .anyMatch(message -> message.content().contains("Implement the approved plan"));
            if (executing || directExecution.get()) {
                directExecution.set(true);
                int executionCall = executionCalls.getAndIncrement();
                if (executionCall == 0) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall(
                                "approved-plan-tool", "git_status",
                                io.github.liumaishenjian.ccjava.domain.JsonObject.empty())));
                if (executionCall == 1) return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("approved plan executed");
                throw new IllegalStateException("Plan fixture 收到过多执行请求");
            }
            int call = calls.getAndIncrement();
            if (call == 0) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-update", "revise_plan_artifact",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "markdown", markdown, "expectedRevision", 0, "expectedContentDigest", "")))));
            if (call == 1) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-evidence", "declare_plan_evidence",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "requirementId", "git-check", "kind", "VERIFICATION", "locator", "git_status",
                                    "label", "workspace status inspected", "required", true)))));
            if (call == 2) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-review", "request_plan_review",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "revision", 2, "contentDigest", digest)))));
            if (call == 3) return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("planning finished");
            if (call == 4) {
                boolean feedbackReachedModel = request.messages().stream()
                        .filter(io.github.liumaishenjian.ccjava.domain.UserMessage.class::isInstance)
                        .map(io.github.liumaishenjian.ccjava.domain.UserMessage.class::cast)
                        .anyMatch(message -> message.content().equals("add rollback verification"));
                if (!feedbackReachedModel) throw new IllegalStateException("Plan feedback 未进入新的模型回合");
                return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                        new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-revise-feedback", "revise_plan_artifact",
                                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                        "markdown", revisedMarkdown, "expectedRevision", 4,
                                        "expectedContentDigest", digest)))));
            }
            if (call == 5) return io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("plan-review-feedback", "request_plan_review",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "revision", 5, "contentDigest", revisedDigest)))));
            if (call == 6) return io.github.liumaishenjian.ccjava.domain.ModelTurn.text("replanning finished");
            throw new IllegalStateException("Plan fixture 收到过多规划请求");
        };
        Path providerRoot = Files.createTempDirectory("cc-java-plan-provider-fixture-");
        Path providerHome = Files.createDirectory(providerRoot.resolve("home"));
        Path providerRepository = Files.createDirectory(providerRoot.resolve("repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(providerHome);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(providerHome);
        var providerAuth = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials,
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                        new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(providerRepository),
                        definitions, credentials),
                Map.of("CC_JAVA_PLAN_FIXTURE_KEY", "fixture-provider-sentinel"));
        providerAuth.login(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                        "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                                .ProviderAuthApplicationService.RefKind.ENV,
                        "CC_JAVA_PLAN_FIXTURE_KEY", true),
                null, io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        providerAuth.addModel("anthropic", "fixture-model", true,
                io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler((events, approvals) ->
                io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession.production(
                        model, events,
                        new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                workspace.toAbsolutePath().normalize(), "fixture-model", Duration.ofSeconds(5)),
                        approvals), providerAuth);
        return ownedFixtureHandler(delegate, providerRoot.getParent(), providerRoot,
                "cc-java-plan-provider-fixture-");
    }

    static StdioProtocol.CommandHandler planRuntimeHandlerForTest(Path workspace) throws Exception {
        return planRuntimeHandler(workspace);
    }

    /** 为 TUI→真实 Java 权限测试提供三个同 source/selector Patch 的确定性 Runtime。 */
    private static StdioProtocol.CommandHandler permissionRuntimeHandler(Path parent) throws Exception {
        Path expectedParent = parent.toAbsolutePath().normalize();
        Path expectedRealParent = expectedParent.toRealPath();
        Path workspace = Files.createTempDirectory(expectedRealParent, "permission-runtime-");
        Path target = workspace.resolve("permission-e2e.txt");
        Files.writeString(target, "old" + System.lineSeparator());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> switch (calls.getAndIncrement()) {
            case 0 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    new io.github.liumaishenjian.ccjava.domain.ToolCall("read-before-patch", "read_file",
                            new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                                    "path", "permission-e2e.txt")))));
            case 1 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-once", "old", "middle")));
            case 2 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-session", "middle", "session")));
            case 3 -> io.github.liumaishenjian.ccjava.domain.ModelTurn.tools(List.of(
                    patch("patch-session-reused", "session", "new")));
            default -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("permission fixture completed");
        };
        RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler((events, approvals) ->
                new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                        model, events,
                        new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                workspace.toAbsolutePath().normalize(), "fixture-model", Duration.ofSeconds(5)),
                        approvals));
        return ownedFixtureHandler(delegate, expectedParent, expectedRealParent, workspace,
                "permission-runtime-");
    }

    private static io.github.liumaishenjian.ccjava.domain.ToolCall patch(
            String id, String oldText, String newText) {
        return new io.github.liumaishenjian.ccjava.domain.ToolCall(id, "apply_patch",
                new io.github.liumaishenjian.ccjava.domain.JsonObject(Map.of(
                        "path", "permission-e2e.txt", "oldText", oldText, "newText", newText)));
    }

    private static StdioProtocol.CommandHandler ownedFixtureHandler(
            RuntimeStdioCommandHandler delegate, Path parent, Path target, String expectedPrefix)
            throws java.io.IOException {
        Path expectedParent = parent.toAbsolutePath().normalize();
        return ownedFixtureHandler(delegate, expectedParent, expectedParent.toRealPath(), target, expectedPrefix);
    }

    private static StdioProtocol.CommandHandler ownedFixtureHandler(
            RuntimeStdioCommandHandler delegate,
            Path expectedParent,
            Path expectedRealParent,
            Path target,
            String expectedPrefix) {
        return new StdioProtocol.CommandHandler() {
            @Override public StdioProtocol.Disposition handle(
                    StdioProtocol.Command command, StdioProtocol.EventEmitter events)
                    throws StdioProtocolException {
                return delegate.handle(command, events);
            }

            @Override public void close() throws Exception {
                try {
                    delegate.close();
                } finally {
                    deleteFixtureTree(expectedParent, expectedRealParent, target, expectedPrefix);
                }
            }
        };
    }

    /**
     * 删除测试 Fixture 前重新证明目标仍是预期父目录内无链接歧义的专用临时目录。
     *
     * @param expectedParent 创建前固定的调用方父目录归一化路径
     * @param expectedRealParent 创建前固定的调用方父目录 real path
     * @param target 待删除的 Fixture 目录
     * @param expectedPrefix 由调用点固定的临时目录名前缀
     * @throws java.io.IOException 目标不安全或删除失败时；安全校验失败绝不吞掉
     */
    static void deleteFixtureTree(
            Path expectedParent,
            Path expectedRealParent,
            Path target,
            String expectedPrefix) throws java.io.IOException {
        Path normalizedParent = expectedParent.toAbsolutePath().normalize();
        Path realParent = expectedRealParent.toAbsolutePath().normalize();
        if (!normalizedParent.equals(expectedParent) || !realParent.equals(expectedRealParent)
                || !normalizedParent.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(realParent)
                || isLinkOrReparse(normalizedParent)) {
            throw new java.io.IOException("Fixture parent identity changed");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        String fileName = normalizedTarget.getFileName() == null ? "" : normalizedTarget.getFileName().toString();
        Path targetParent = normalizedTarget.getParent();
        if (!normalizedParent.equals(targetParent) || normalizedTarget.equals(normalizedParent)
                || expectedPrefix == null || expectedPrefix.isBlank() || !fileName.startsWith(expectedPrefix)
                || isLinkOrReparse(normalizedTarget)) {
            throw new java.io.IOException("Unsafe fixture cleanup target");
        }
        Path realTarget = normalizedTarget.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realTarget.getParent().equals(realParent) || !realTarget.equals(normalizedTarget)) {
            throw new java.io.IOException("Ambiguous fixture cleanup target");
        }
        java.util.List<Path> entries;
        try (var paths = Files.walk(normalizedTarget)) {
            entries = paths.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            BasicFileAttributes attributes = Files.readAttributes(
                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path realEntry = entry.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (isLinkOrReparse(entry) || (!attributes.isDirectory() && !attributes.isRegularFile())
                    || !realEntry.startsWith(realTarget)) {
                throw new java.io.IOException("Fixture cleanup contains a link or escape");
            }
        }
        for (Path entry : entries) Files.delete(entry);
    }

    private static boolean isLinkOrReparse(Path path) throws java.io.IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes basic = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (basic.isOther()) return true;
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    static StdioProtocol.CommandHandler providerControlHandlerForTest() throws Exception {
        return providerControlHandler();
    }

    private static StdioProtocol.CommandHandler providerControlHandler() throws Exception {
        Path root = Files.createTempDirectory("cc-java-provider-control-fixture-");
        Path home = Files.createDirectory(root.resolve("home"));
        Path repository = Files.createDirectory(root.resolve("repository"));
        var credentials = new io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore(home);
        var definitions = new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore(home);
        var migration = new io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService(
                new io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader(repository),
                definitions, credentials);
        var service = new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService(
                definitions, credentials, migration, Map.of("CC_JAVA_FIXTURE_KEY", "fixture-provider-sentinel"));
        service.login(new io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService.LoginRequest(
                "anthropic", "fixture", io.github.liumaishenjian.ccjava.cli.runtime
                        .ProviderAuthApplicationService.RefKind.ENV, "CC_JAVA_FIXTURE_KEY", true),
                null, io.github.liumaishenjian.ccjava.core.CancellationToken.none());
        var application = new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                ignored -> io.github.liumaishenjian.ccjava.domain.ModelTurn.text("unused"),
                io.github.liumaishenjian.ccjava.core.AgentEventSink.noop(),
                new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                        repository, "fixture-model", Duration.ofSeconds(5)));
        RuntimeStdioCommandHandler delegate = new RuntimeStdioCommandHandler(application, service);
        return ownedFixtureHandler(delegate, root.getParent(), root,
                "cc-java-provider-control-fixture-");
    }
}
