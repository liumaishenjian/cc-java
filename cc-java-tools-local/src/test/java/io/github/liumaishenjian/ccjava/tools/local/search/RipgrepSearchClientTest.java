package io.github.liumaishenjian.ccjava.tools.local.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepSearchClientTest {

    @TempDir
    Path workspace;

    @Test
    void searchesWithIgnoreAndSensitivePreFilters() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("src/App.java"), "class App { // needle\n}\n");
        Files.writeString(workspace.resolve("src/TokenBudget.java"), "class TokenBudget { // needle\n}\n");
        Files.writeString(workspace.resolve(".env"), "needle secret\n");
        Files.writeString(workspace.resolve(".env.example"), "needle template\n");
        Files.writeString(workspace.resolve(".git/config"), "needle internal\n");
        Files.writeString(workspace.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(workspace.resolve("ignored.txt"), "needle ignored\n");

        RipgrepSearchClient client = new RipgrepSearchClient(workspace);
        TextSearchBackend.SearchResult result;
        try {
            result = client.search("need(le|ing)", ".", null, true, true);
        } catch (TextSearchBackend.SearchException exception) {
            Assumptions.assumeTrue(
                    exception.error().code() != ToolErrorCode.SEARCH_UNAVAILABLE,
                    "当前环境没有 rg");
            throw exception;
        }

        assertThat(result.lines()).hasSize(3);
        assertThat(result.lines()).anyMatch(line -> line.contains("src")
                && line.contains("App.java") && line.contains(":1:"));
        assertThat(result.lines()).anyMatch(line -> line.contains("TokenBudget.java"));
        assertThat(result.lines()).anyMatch(line -> line.contains(".env.example"));
        assertThat(result.lines()).noneMatch(line ->
                line.contains(".env:") || line.contains(".env\\:")
                        || line.contains(".git") || line.contains("ignored.txt"));
    }

    @Test
    void resolvesTrustedExecutableWithoutShell() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        RipgrepExecutableResolver resolver = () -> {
            resolutions.incrementAndGet();
            return fakeCommand("success");
        };
        RipgrepSearchClient client =
                new RipgrepSearchClient(workspace, resolver, Duration.ofSeconds(2));

        TextSearchBackend.SearchResult result =
                client.search("$(PRIVATE_SENTINEL)", ".", null, true, false);

        assertThat(resolutions).hasValue(1);
        assertThat(result.lines()).containsExactly("src/App.java:1:needle");
    }

    @Test
    void executesStructuredContentFilesAndCountRequestsAgainstRealRipgrep() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "before\nNeedle\nalpha\nbeta\n");
        Files.writeString(workspace.resolve("README.md"), "Needle docs\n");
        RipgrepSearchClient client = new RipgrepSearchClient(workspace);

        RipgrepParsedResult content = structuredOrSkip(client, request(
                TextSearchMode.CONTENT, true, true, 1, 0));
        assertThat(content.content()).anyMatch(line ->
                line.kind() == RipgrepJsonEvent.LineKind.MATCH
                        && line.path().replace('\\', '/').endsWith("src/App.java"));
        assertThat(content.content()).anyMatch(line ->
                line.kind() == RipgrepJsonEvent.LineKind.CONTEXT
                        && line.text().contains("before"));

        RipgrepParsedResult multiline = structuredOrSkip(client, new TextSearchRequest(
                "alpha\\nbeta",
                "src/App.java",
                "**/*.java",
                "java",
                TextSearchMode.CONTENT,
                true,
                true,
                true,
                true,
                0,
                0,
                0,
                100,
                SearchCancellation.none()));
        assertThat(multiline.content()).anyMatch(line ->
                line.kind() == RipgrepJsonEvent.LineKind.MATCH
                        && line.text().contains("alpha\nbeta"));

        RipgrepParsedResult files = structuredOrSkip(client, request(
                TextSearchMode.FILES, false, false, 0, 0));
        assertThat(files.files()).anyMatch(path ->
                path.replace('\\', '/').endsWith("src/App.java"));

        RipgrepParsedResult counts = structuredOrSkip(client, request(
                TextSearchMode.COUNT, false, false, 0, 0));
        assertThat(counts.counts().values()).contains(1L);
    }

    @Test
    void boundsOutputAndDoesNotExposeStderr() {
        assertThat(catchMode("overflow", Duration.ofSeconds(2)).error().code())
                .isEqualTo(ToolErrorCode.OUTPUT_LIMIT_EXCEEDED);

        TextSearchBackend.SearchException error = catchMode("error", Duration.ofSeconds(2));
        assertThat(error.error().code()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(error.error().message()).doesNotContain("PRIVATE_STDERR_SENTINEL");
        assertThat(error.getMessage()).doesNotContain("PRIVATE_STDERR_SENTINEL");
    }

    @Test
    void retriesOnlyExplicitTransientResourceFailure() throws Exception {
        Path attempt = workspace.resolve("attempt.txt");
        RipgrepSearchClient client = client(
                "eagain", Duration.ofSeconds(3),
                "-Dcc.java.fake.rg.attempt=" + attempt);

        TextSearchBackend.SearchResult result =
                client.search("needle", ".", null, true, false);

        assertThat(result.lines()).containsExactly("src/App.java:1:needle");
        assertThat(Files.readString(attempt)).isEqualTo("2");
    }

    @Test
    void stopsAfterOneReducedThreadRetry() throws Exception {
        Path attempt = workspace.resolve("attempt.txt");
        RipgrepSearchClient client = client(
                "eagain-always", Duration.ofSeconds(3),
                "-Dcc.java.fake.rg.attempt=" + attempt);

        TextSearchBackend.SearchException error =
                catchSearch(client, SearchCancellation.none());

        assertThat(error.error().code()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(Files.readString(attempt)).isEqualTo("2");
    }

    @Test
    void doesNotRetryDeterministicSyntaxFailureOrExposeDiagnostic() throws Exception {
        Path attempt = workspace.resolve("attempt.txt");
        RipgrepSearchClient client = client(
                "syntax", Duration.ofSeconds(3),
                "-Dcc.java.fake.rg.attempt=" + attempt);

        TextSearchBackend.SearchException error =
                catchSearch(client, SearchCancellation.none());

        assertThat(error.error().code()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(error.error().message()).doesNotContain("PRIVATE_STDERR_SENTINEL");
        assertThat(Files.readString(attempt)).isEqualTo("1");
    }

    @Test
    void terminatesTimedOutProcessAndDescendant() throws Exception {
        Path pid = workspace.resolve("parent.pid");
        Path childPid = workspace.resolve("child.pid");
        RipgrepSearchClient client = client(
                "descendant", Duration.ofSeconds(3),
                "-Dcc.java.fake.rg.pid=" + pid,
                "-Dcc.java.fake.rg.childPid=" + childPid);

        CompletableFuture<TextSearchBackend.SearchException> result =
                CompletableFuture.supplyAsync(() ->
                        catchSearch(client, SearchCancellation.none()));
        awaitFile(pid);
        awaitFile(childPid);
        TextSearchBackend.SearchException error = result.get(5, TimeUnit.SECONDS);

        assertThat(error.error().code()).isEqualTo(ToolErrorCode.OPERATION_TIMED_OUT);
        assertProcessStops(readPid(pid));
        assertProcessStops(readPid(childPid));
    }

    @Test
    void cancellationStopsRunningProcessAndDescendant() throws Exception {
        Path pid = workspace.resolve("parent.pid");
        Path childPid = workspace.resolve("child.pid");
        AtomicBoolean cancelled = new AtomicBoolean();
        RipgrepSearchClient client = client(
                "descendant", Duration.ofSeconds(5),
                "-Dcc.java.fake.rg.pid=" + pid,
                "-Dcc.java.fake.rg.childPid=" + childPid);

        CompletableFuture<TextSearchBackend.SearchException> result =
                CompletableFuture.supplyAsync(() ->
                        catchSearch(client, SearchCancellation.from(cancelled::get)));
        awaitFile(pid);
        awaitFile(childPid);
        cancelled.set(true);

        TextSearchBackend.SearchException error = result.get(3, TimeUnit.SECONDS);
        assertThat(error.error().code()).isEqualTo(ToolErrorCode.OPERATION_CANCELLED);
        assertProcessStops(readPid(pid));
        assertProcessStops(readPid(childPid));
    }

    @Test
    void rejectsPreCancelledSearchWithoutStartingProcess() {
        Path pid = workspace.resolve("not-started.pid");
        RipgrepSearchClient client = client(
                "success", Duration.ofSeconds(2),
                "-Dcc.java.fake.rg.pid=" + pid);

        TextSearchBackend.SearchException error =
                catchSearch(client, () -> true);

        assertThat(error.error().code()).isEqualTo(ToolErrorCode.OPERATION_CANCELLED);
        assertThat(pid).doesNotExist();
    }

    private TextSearchBackend.SearchException catchMode(String mode, Duration timeout) {
        return catchSearch(client(mode, timeout), SearchCancellation.none());
    }

    private TextSearchRequest request(
            TextSearchMode mode,
            boolean regex,
            boolean multiline,
            int beforeContext,
            int afterContext) {
        return new TextSearchRequest(
                regex ? "Need(le)?" : "Needle",
                ".",
                null,
                "java",
                mode,
                true,
                regex,
                multiline,
                true,
                beforeContext,
                afterContext,
                0,
                100,
                SearchCancellation.none());
    }

    private RipgrepParsedResult structuredOrSkip(
            RipgrepSearchClient client,
            TextSearchRequest request) throws Exception {
        try {
            return client.searchStructured(request);
        } catch (TextSearchBackend.SearchException exception) {
            Assumptions.assumeTrue(
                    exception.error().code() != ToolErrorCode.SEARCH_UNAVAILABLE,
                    "当前环境没有 rg");
            throw exception;
        }
    }

    private TextSearchBackend.SearchException catchSearch(
            RipgrepSearchClient client,
            SearchCancellation cancellation) {
        try {
            client.search("needle", ".", null, true, false, cancellation);
            throw new AssertionError("预期搜索失败");
        } catch (TextSearchBackend.SearchException exception) {
            return exception;
        }
    }

    private RipgrepSearchClient client(
            String mode,
            Duration timeout,
            String... additionalJvmArguments) {
        ArrayList<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dcc.java.fake.rg.mode=" + mode);
        command.addAll(List.of(additionalJvmArguments));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeRipgrepProcess.class.getName());
        return new RipgrepSearchClient(workspace, command, timeout);
    }

    private List<String> fakeCommand(String mode) {
        return List.of(
                javaExecutable(),
                "-Dcc.java.fake.rg.mode=" + mode,
                "-cp",
                System.getProperty("java.class.path"),
                FakeRipgrepProcess.class.getName());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(path).exists();
    }

    private static long readPid(Path path) throws Exception {
        awaitFile(path);
        return Long.parseLong(Files.readString(path));
    }

    private static void assertProcessStops(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (isAlive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(isAlive(pid)).as("进程 %s 不应成为孤儿", pid).isFalse();
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
