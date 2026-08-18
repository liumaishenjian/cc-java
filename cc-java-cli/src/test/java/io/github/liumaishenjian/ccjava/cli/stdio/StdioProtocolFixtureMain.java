package io.github.liumaishenjian.ccjava.cli.stdio;

import java.nio.file.Files;
import java.nio.file.Path;
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
        try {
            StdioProtocol.CommandHandler handler = args.length == 1 && args[0].equals("provider-control")
                    ? providerControlHandler()
                    : args.length == 2 && args[0].equals("plan-runtime")
                            ? planRuntimeHandler(Path.of(args[1]))
                            : new FakeStdioCommandHandler(List.of("alpha ", "beta"), Duration.ofMillis(250));
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(System.in, System.out, handler).run();
            if (reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR) {
                System.exit(2);
            }
            if (handler instanceof AutoCloseable closeable) closeable.close();
        } catch (Exception exception) {
            System.err.println("Fake stdio fixture failed");
            System.exit(2);
        }
    }

    private static RuntimeStdioCommandHandler planRuntimeHandler(Path workspace) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        io.github.liumaishenjian.ccjava.core.ModelGateway model = request -> {
            if (calls.getAndIncrement() == 0) {
                return io.github.liumaishenjian.ccjava.domain.ModelTurn.text(
                        "{\"objective\":\"cross-process plan\",\"steps\":[{\"title\":\"inspect\","
                                + "\"detail\":\"read workspace safely\"}]}");
            }
            throw new IllegalStateException("Plan fixture 不应收到额外模型请求");
        };
        return new RuntimeStdioCommandHandler((events, approvals) ->
                new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession(
                        model, events,
                        new io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions(
                                workspace.toAbsolutePath().normalize(), "fixture-model", Duration.ofSeconds(5)),
                        approvals));
    }

    private static RuntimeStdioCommandHandler providerControlHandler() throws Exception {
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
        return new RuntimeStdioCommandHandler(application, service);
    }
}
