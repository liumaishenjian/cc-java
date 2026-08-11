package io.github.liumaishenjian.ccjava.cli.daemon;

import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessAgentApplicationService;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeOptions;
import io.github.liumaishenjian.ccjava.cli.runtime.HeadlessRuntimeSession;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 仅供 OS-process E2E 的真实 daemon composition 入口。
 *
 * <p>入口复用生产 Application/Runtime、ownership、stable handler 与 loopback transport，但用
 * deterministic Fake Model 避免凭证和网络依赖。完整 CLI 参数 dispatch 由 {@code CcJavaCommandTest}
 * 单独证明。</p>
 */
public final class StableDaemonProcessFixtureMain {
    private StableDaemonProcessFixtureMain() {
    }

    /**
     * 启动测试专用独立 JVM，输出一次端口/token 后等待 stable shutdown。
     *
     * @param args 可选的隔离状态根目录
     * @throws Exception Runtime、监听或清理失败
     */
    public static void main(String[] args) throws Exception {
        Path base = args.length == 0
                ? Files.createTempDirectory("cc-java-daemon-e2e-")
                : Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path workspace = base.resolve("workspace");
        Files.createDirectories(workspace);
        var options = new HeadlessRuntimeOptions(
                workspace,
                "fixture",
                Duration.ofSeconds(5),
                PermissionMode.DEFAULT,
                List.of(),
                SessionOpenRequest.create(),
                base.resolve("sessions"));
        HeadlessRuntimeSession runtime = new HeadlessRuntimeSession(
                request -> ModelTurn.text("fixture-final"), AgentEventSink.noop(), options);
        runtime.open();
        try (DaemonOwnership ownership = DaemonOwnership.acquire(base.resolve("daemon"))) {
            CapabilityToken token = ownership.token();
            var handler = new StableProtocolHandler(
                    token,
                    Set.of(ProtocolFeature.RUN, ProtocolFeature.CANCEL, ProtocolFeature.DAEMON),
                    new HeadlessAgentApplicationService(runtime));
            try (StableLoopbackDaemon daemon = new StableLoopbackDaemon(0, token, handler)) {
                daemon.start();
                System.out.println("READY " + daemon.port() + " " + token.reveal());
                System.out.flush();
                daemon.awaitClosed();
            }
        } finally {
            try (var walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
