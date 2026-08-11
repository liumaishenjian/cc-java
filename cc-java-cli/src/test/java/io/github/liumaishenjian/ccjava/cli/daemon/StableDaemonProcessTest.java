package io.github.liumaishenjian.ccjava.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.protocol.ProtocolEnvelope;
import io.github.liumaishenjian.ccjava.protocol.ProtocolMessageKind;
import io.github.liumaishenjian.ccjava.protocol.ProtocolVersion;
import io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * 验证真实独立 JVM daemon composition 的认证、协商、Run、事件和清理。
 *
 * <p>本测试用 deterministic Fake Model 装配真实 Application/Runtime/HTTP 传输，因此不启动
 * Provider 配置链；它与 {@code CcJavaCommandTest} 的 {@code --daemon} dispatch 测试共同证明 CLI
 * entry 和独立 OS process 生命周期，不能单独冒充完整 Provider CLI E2E。</p>
 */
class StableDaemonProcessTest {
    @Test
    void realProcessCompletesStableLifecycleAndExitsThenOwnershipCanBeReacquired() throws Exception {
        Path base = Files.createTempDirectory("cc-java-daemon-process-test-");
        Process process = start(base);
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String ready = output.readLine();
            assertThat(ready).startsWith("READY ");
            String[] fields = ready.split(" ");
            URI endpoint = URI.create("http://127.0.0.1:" + fields[1]);
            String token = fields[2];
            HttpClient client = HttpClient.newHttpClient();

            assertThat(post(client, endpoint, "bad", new byte[] {1}).statusCode()).isEqualTo(401);
            assertThat(post(
                    client,
                    endpoint,
                    token,
                    new byte[StableProtocolCodec.MAX_LINE_BYTES + 1]).statusCode()).isEqualTo(413);

            StableProtocolCodec codec = new StableProtocolCodec();
            ObjectNode initialize = codec.objectNode().put("token", token).put("version", "1.0");
            initialize.putArray("features").add("RUN").add("DAEMON");
            assertThat(post(
                    client,
                    endpoint,
                    token,
                    codec.encode(request(codec, "initialize", "i", 1, initialize))).statusCode()).isEqualTo(202);
            assertThat(next(client, endpoint, token, codec).type()).isEqualTo("initialized");

            ObjectNode run = codec.objectNode().put("prompt", "hello");
            assertThat(post(
                    client,
                    endpoint,
                    token,
                    codec.encode(request(codec, "run.start", "r", 2, run))).statusCode()).isEqualTo(202);
            boolean accepted = false;
            boolean event = false;
            boolean terminal = false;
            int terminals = 0;
            for (int index = 0; index < 30 && !terminal; index++) {
                ProtocolEnvelope value = next(client, endpoint, token, codec);
                accepted |= "run.accepted".equals(value.type());
                event |= "run.event".equals(value.type());
                if ("run.terminal".equals(value.type())) {
                    terminal = true;
                    terminals++;
                }
            }
            assertThat(accepted).isTrue();
            assertThat(event).isTrue();
            assertThat(terminals).isOne();
            assertThat(post(
                    client,
                    endpoint,
                    token,
                    codec.encode(request(codec, "shutdown", "s", 3, codec.objectNode()))).statusCode()).isEqualTo(202);
        } finally {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isBlank();
        try (DaemonOwnership ownership = DaemonOwnership.acquire(base.resolve("daemon"))) {
            assertThat(ownership.token()).isNotNull();
        }
        try (var walk = Files.walk(base)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Process start(Path base) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        assertThat(Files.isRegularFile(java)).isTrue();
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                StableDaemonProcessFixtureMain.class.getName(),
                base.toString()).start();
    }

    private static HttpResponse<byte[]> post(
            HttpClient client, URI base, String token, byte[] body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(base.resolve("/v1/message"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ProtocolEnvelope next(
            HttpClient client, URI base, String token, StableProtocolCodec codec) throws Exception {
        for (int index = 0; index < 100; index++) {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(base.resolve("/v1/events"))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return codec.decode(response.body());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("daemon event timeout");
    }

    private static ProtocolEnvelope request(
            StableProtocolCodec codec,
            String type,
            String id,
            long sequence,
            ObjectNode payload) {
        return new ProtocolEnvelope(
                ProtocolVersion.V1_0,
                ProtocolMessageKind.REQUEST,
                type,
                id,
                "client",
                Optional.empty(),
                Optional.empty(),
                sequence,
                Optional.empty(),
                payload);
    }
}
