package io.github.liumaishenjian.ccjava.cli.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StdioProtocolProcessTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void realJavaProcessCancelsRunAndExitsWithoutProtocolNoise()
            throws Exception {
        Process process = startFixtureProcess();
        List<ProcessHandle> descendants = new ArrayList<>();
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                     process.getOutputStream(),
                     StandardCharsets.UTF_8));
             BufferedReader output = new BufferedReader(new InputStreamReader(
                     process.getInputStream(),
                     StandardCharsets.UTF_8))) {
            JsonMapper mapper = JsonMapper.builder().build();

            send(input,
                    "{\"version\":0,\"type\":\"initialize\","
                            + "\"requestId\":\"req-1\",\"sequence\":1,\"payload\":{}}");
            JsonNode initialized = readEvent(process, output, mapper);
            assertThat(initialized.get("type").stringValue()).isEqualTo("initialized");
            String sessionId = initialized.get("sessionId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.start\","
                            + "\"requestId\":\"req-2\",\"sessionId\":\"%s\","
                            + "\"sequence\":2,\"payload\":{\"prompt\":\"cancel me\"}}")
                            .formatted(sessionId));
            JsonNode started = readEvent(process, output, mapper);
            assertThat(started.get("type").stringValue()).isEqualTo("run.started");
            String runId = started.get("runId").stringValue();

            send(input,
                    ("{\"version\":0,\"type\":\"run.cancel\","
                            + "\"requestId\":\"req-3\",\"sessionId\":\"%s\","
                            + "\"runId\":\"%s\",\"sequence\":3,\"payload\":{}}")
                            .formatted(sessionId, runId));

            JsonNode terminal = readUntilTerminal(process, output, mapper);
            assertThat(terminal.get("type").stringValue()).isEqualTo("run.cancelled");
            assertThat(terminal.get("runId").stringValue()).isEqualTo(runId);

            descendants.addAll(process.toHandle().descendants().toList());
            send(input,
                    ("{\"version\":0,\"type\":\"shutdown\","
                            + "\"requestId\":\"req-4\",\"sessionId\":\"%s\","
                            + "\"sequence\":4,\"payload\":{}}")
                            .formatted(sessionId));
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }

        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(stderr).isBlank();
        assertThat(process.isAlive()).isFalse();
        assertThat(descendants).noneMatch(ProcessHandle::isAlive);
    }

    private Process startFixtureProcess() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaExecutable = javaHome.resolve("bin").resolve(
                System.getProperty("os.name").startsWith("Windows")
                        ? "java.exe"
                        : "java");
        assertThat(Files.isRegularFile(javaExecutable)).isTrue();
        return new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                StdioProtocolFixtureMain.class.getName())
                .directory(Path.of("").toAbsolutePath().toFile())
                .start();
    }

    private void send(BufferedWriter input, String message) throws IOException {
        input.write(message.strip());
        input.newLine();
        input.flush();
    }

    private JsonNode readUntilTerminal(
            Process process,
            BufferedReader output,
            JsonMapper mapper) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            JsonNode event = readEvent(process, output, mapper);
            String type = event.get("type").stringValue();
            if (type.equals("run.completed")
                    || type.equals("run.failed")
                    || type.equals("run.cancelled")) {
                return event;
            }
        }
        throw new AssertionError("未在事件上限内收到 Run 终态");
    }

    private JsonNode readEvent(
            Process process,
            BufferedReader output,
            JsonMapper mapper)
            throws Exception {
        long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
        while (!output.ready()) {
            if (!process.isAlive()) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("等待子进程协议事件超时");
            }
            Thread.sleep(10);
        }
        String line = output.readLine();
        assertThat(line).as("子进程必须输出协议事件").isNotNull();
        JsonNode event = mapper.readTree(line);
        assertThat(event.isObject()).isTrue();
        return event;
    }
}
