package io.github.liumaishenjian.ccjava.tools.local.search;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过结构化参数执行 ripgrep 的只读搜索适配器。
 *
 * <p>本类型不接受 Shell 字符串。可执行入口只来自可信解析器，工作目录和目标目录均由
 * WorkspaceGuard 固定。适配器负责进程墙钟、取消、进程树清理、并发排空标准输出与
 * 标准错误，以及仅针对临时资源不足的有界重试；原始 stderr 永远不会进入 Tool 结果。</p>
 *
 * @since 0.3.1
 */
public final class RipgrepSearchClient implements TextSearchBackend {

    private static final int MAX_STDOUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final int MAX_ATTEMPTS = 2;
    private static final long POLL_MILLIS = 20;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROCESS_STOP_GRACE = Duration.ofMillis(250);
    private static final Duration STREAM_DRAIN_GRACE = Duration.ofSeconds(1);
    private static final List<String> SENSITIVE_GLOBS = List.of(
            "!.git/**", "!**/.git/**",
            "!config/provider.local.properties",
            "!**/.env",
            "!**/.env.local", "!**/.env.development", "!**/.env.production",
            "!**/.env.test", "!**/.env.staging",
            "!**/.env.*.local",
            "!**/*.pem", "!**/*.key", "!**/*.p12", "!**/*.pfx",
            "!**/*.jks", "!**/*.keystore",
            "!**/id_rsa", "!**/id_dsa", "!**/id_ecdsa", "!**/id_ed25519",
            "!**/*credential*", "!**/*secret*",
            "!**/token", "!**/tokens", "!**/token.*", "!**/tokens.*",
            "!**/*-token", "!**/*_token", "!**/*.token",
            "!**/*-tokens", "!**/*_tokens", "!**/*.tokens",
            "!**/*-token.*", "!**/*_token.*", "!**/*.token.*",
            "!**/*-tokens.*", "!**/*_tokens.*", "!**/*.tokens.*",
            "!**/token-*", "!**/token_*", "!**/token.*",
            "!**/tokens-*", "!**/tokens_*", "!**/tokens.*");

    private final Path workspace;
    private final RipgrepExecutableResolver executableResolver;
    private final Duration timeout;
    private final RipgrepJsonLinesParser jsonParser;

    /**
     * 创建固定 Workspace 的 ripgrep 客户端。
     *
     * @param workspace 真实 Workspace
     * @param executable ripgrep 可执行文件名或路径
     */
    public RipgrepSearchClient(Path workspace, String executable) {
        this(workspace, List.of(executable));
    }

    /**
     * 使用固定命令前缀创建客户端，供嵌入式启动器和进程负例测试复用。
     *
     * @param workspace 真实 Workspace
     * @param commandPrefix 不含模型输入的可执行文件及固定前置参数
     */
    public RipgrepSearchClient(Path workspace, List<String> commandPrefix) {
        this(workspace, RipgrepExecutableResolver.fixed(commandPrefix), DEFAULT_TIMEOUT);
    }

    /**
     * 使用显式墙钟限制创建客户端。
     *
     * @param workspace 真实 Workspace
     * @param commandPrefix 固定命令前缀
     * @param timeout 整次搜索（包括重试）的墙钟限制
     */
    public RipgrepSearchClient(
            Path workspace,
            List<String> commandPrefix,
            Duration timeout) {
        this(workspace, RipgrepExecutableResolver.fixed(commandPrefix), timeout);
    }

    /**
     * 使用可替换的可执行入口解析器创建客户端。
     *
     * @param workspace 真实 Workspace
     * @param executableResolver 可信可执行入口解析器
     * @param timeout 整次搜索（包括重试）的墙钟限制
     */
    public RipgrepSearchClient(
            Path workspace,
            RipgrepExecutableResolver executableResolver,
            Duration timeout) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.executableResolver =
                Objects.requireNonNull(executableResolver, "executableResolver 不能为空");
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        this.jsonParser = new RipgrepJsonLinesParser();
    }

    /**
     * 使用系统 {@code PATH} 中的 {@code rg} 创建客户端。
     *
     * @param workspace 真实 Workspace
     */
    public RipgrepSearchClient(Path workspace) {
        this(workspace, RipgrepExecutableResolver.systemPath(), DEFAULT_TIMEOUT);
    }

    /**
     * 执行未绑定外部取消信号的搜索。
     *
     * @param query 查询文本或正则表达式
     * @param protocolRoot 已校验的协议目录
     * @param glob 可选文件 Glob
     * @param caseSensitive 是否区分大小写
     * @param regex 是否把查询解释为正则表达式
     * @return 相对路径、行号和正文组成的原始行
     * @throws SearchException rg 不可用、超时、输出超限或执行失败
     */
    @Override
    public SearchResult search(
            String query,
            String protocolRoot,
            String glob,
            boolean caseSensitive,
            boolean regex) throws TextSearchBackend.SearchException {
        return search(
                query, protocolRoot, glob, caseSensitive, regex, SearchCancellation.none());
    }

    /**
     * 执行可取消的有界搜索。
     *
     * @param query 查询文本或正则表达式
     * @param protocolRoot 已校验的协议目录
     * @param glob 可选文件 Glob
     * @param caseSensitive 是否区分大小写
     * @param regex 是否把查询解释为正则表达式
     * @param cancellation 当前调用的取消状态
     * @return 相对路径、行号和正文组成的原始行
     * @throws SearchException 搜索取消、rg 不可用、超时、输出超限或执行失败
     */
    @Override
    public SearchResult search(
            String query,
            String protocolRoot,
            String glob,
            boolean caseSensitive,
            boolean regex,
            SearchCancellation cancellation) throws TextSearchBackend.SearchException {
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        List<String> command = buildCommand(query, protocolRoot, glob, caseSensitive, regex);
        return executeWithRetry(command, cancellation);
    }

    /**
     * 以 ripgrep JSON Lines 机器协议执行完整搜索请求。
     *
     * @param request 已校验的类型化请求
     * @return 路径仍待 WorkspaceGuard 复验的结构化结果
     * @throws SearchException 取消、超时、进程失败或协议损坏
     */
    @Override
    public RipgrepParsedResult searchStructured(TextSearchRequest request)
            throws TextSearchBackend.SearchException {
        Objects.requireNonNull(request, "request 不能为空");
        SearchResult raw = executeWithRetry(
                buildStructuredCommand(request), request.cancellation());
        try {
            return jsonParser.parse(raw.lines());
        } catch (RipgrepJsonParseException exception) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.SEARCH_PROTOCOL_VIOLATION,
                    "ripgrep 搜索结果协议无效"));
        }
    }

    private List<String> buildCommand(
            String query,
            String protocolRoot,
            String glob,
            boolean caseSensitive,
            boolean regex) {
        ArrayList<String> command = new ArrayList<>(
                RipgrepExecutableResolver.validate(executableResolver.resolveCommandPrefix()));
        command.addAll(List.of(
                "--hidden",
                "--color", "never",
                "--no-heading",
                "--with-filename",
                "--line-number",
                "--max-columns", "500",
                "--max-filesize", "2M"));
        if (!regex) {
            command.add("--fixed-strings");
        }
        if (!caseSensitive) {
            command.add("--ignore-case");
        }
        for (String exclusion : SENSITIVE_GLOBS) {
            command.add("--iglob");
            command.add(exclusion);
        }
        if (glob != null) {
            command.add("--glob");
            command.add(glob);
        }
        command.add("-e");
        command.add(query);
        command.add("--");
        command.add(protocolRoot);
        return List.copyOf(command);
    }

    private List<String> buildStructuredCommand(TextSearchRequest request) {
        ArrayList<String> command = new ArrayList<>(
                RipgrepExecutableResolver.validate(executableResolver.resolveCommandPrefix()));
        command.addAll(List.of(
                "--no-config",
                "--json",
                "--hidden",
                "--sort", "path",
                "--max-filesize", "2M"));
        if (!request.regex()) {
            command.add("--fixed-strings");
        }
        if (!request.caseSensitive()) {
            command.add("--ignore-case");
        }
        if (request.multiline()) {
            command.add("--multiline");
        }
        if (request.fileType() != null) {
            command.add("--type");
            command.add(request.fileType());
        }
        if (request.mode() == TextSearchMode.CONTENT) {
            if (request.beforeContext() > 0) {
                command.add("--before-context");
                command.add(Integer.toString(request.beforeContext()));
            }
            if (request.afterContext() > 0) {
                command.add("--after-context");
                command.add(Integer.toString(request.afterContext()));
            }
        } else if (request.mode() == TextSearchMode.FILES) {
            command.add("--max-count");
            command.add("1");
        }
        for (String exclusion : SENSITIVE_GLOBS) {
            command.add("--iglob");
            command.add(exclusion);
        }
        if (request.glob() != null) {
            command.add("--glob");
            command.add(request.glob());
        }
        command.add("-e");
        command.add(request.query());
        command.add("--");
        command.add(request.protocolRoot());
        return List.copyOf(command);
    }

    private SearchResult executeWithRetry(
            List<String> command,
            SearchCancellation cancellation) throws TextSearchBackend.SearchException {
        long deadline = deadlineAfter(timeout);
        AttemptFailure lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            rejectCancellation(cancellation);
            try {
                List<String> attemptCommand =
                        attempt == 1 ? command : withSingleThread(command);
                return executeAttempt(attemptCommand, cancellation, deadline);
            } catch (AttemptFailure failure) {
                lastFailure = failure;
                if (!failure.retryable() || attempt == MAX_ATTEMPTS) {
                    throw new TextSearchBackend.SearchException(failure.error());
                }
                waitBeforeRetry(attempt, cancellation, deadline);
            }
        }
        throw new TextSearchBackend.SearchException(Objects.requireNonNull(lastFailure).error());
    }

    private static List<String> withSingleThread(List<String> command) {
        ArrayList<String> reduced = new ArrayList<>(command);
        int expressionIndex = reduced.indexOf("-e");
        if (expressionIndex < 0) {
            throw new IllegalStateException("ripgrep 命令缺少表达式边界");
        }
        reduced.add(expressionIndex, "--threads");
        reduced.add(expressionIndex + 1, "1");
        return List.copyOf(reduced);
    }

    private SearchResult executeAttempt(
            List<String> command,
            SearchCancellation cancellation,
            long deadline) throws AttemptFailure, TextSearchBackend.SearchException {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.toFile());
            builder.environment().remove("RIPGREP_CONFIG_PATH");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("LANG", "C");
            process = builder.start();
        } catch (IOException exception) {
            ToolError error = ToolError.of(
                    isTransientResourceFailure(exception.getMessage())
                            ? ToolErrorCode.EXECUTION_FAILED
                            : ToolErrorCode.SEARCH_UNAVAILABLE,
                    isTransientResourceFailure(exception.getMessage())
                            ? "ripgrep 临时资源不足"
                            : "ripgrep 搜索引擎不可用");
            throw new AttemptFailure(error, isTransientResourceFailure(exception.getMessage()));
        }

        BoundedBytes stdout = new BoundedBytes(process.getInputStream(), MAX_STDOUT_BYTES);
        BoundedBytes stderr = new BoundedBytes(process.getErrorStream(), MAX_STDERR_BYTES);
        Thread out = Thread.ofVirtual().name("cc-java-rg-stdout").start(stdout);
        Thread err = Thread.ofVirtual().name("cc-java-rg-stderr").start(stderr);

        TerminationReason termination = await(process, stdout, cancellation, deadline);
        if (termination != TerminationReason.NATURAL) {
            terminateProcessTree(process);
        }
        boolean streamsDrained = drainStreams(out, err, stdout, stderr);

        if (termination == TerminationReason.CANCELLED) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_CANCELLED, "ripgrep 搜索已取消"));
        }
        if (termination == TerminationReason.INTERRUPTED) {
            Thread.currentThread().interrupt();
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "ripgrep 搜索已取消"));
        }
        if (termination == TerminationReason.TIMED_OUT) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "ripgrep 搜索超过时间上限"));
        }
        if (termination == TerminationReason.OUTPUT_LIMIT || stdout.exceeded()) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OUTPUT_LIMIT_EXCEEDED, "ripgrep 输出超过字节上限"));
        }
        if (!streamsDrained || stdout.readFailed() || stderr.readFailed()) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.EXECUTION_FAILED, "ripgrep 输出读取失败"));
        }

        int exit = process.exitValue();
        if (exit != 0 && exit != 1) {
            boolean retryable = isTransientResourceFailure(stderr.text());
            throw new AttemptFailure(
                    ToolError.of(
                            ToolErrorCode.EXECUTION_FAILED,
                            retryable ? "ripgrep 临时资源不足" : "ripgrep 搜索失败"),
                    retryable);
        }
        String text = stdout.text();
        return new TextSearchBackend.SearchResult(
                text.isEmpty() ? List.of() : text.lines().toList(),
                stderr.exceeded());
    }

    private static TerminationReason await(
            Process process,
            BoundedBytes stdout,
            SearchCancellation cancellation,
            long deadline) throws TextSearchBackend.SearchException {
        while (process.isAlive()) {
            if (cancellation.isCancellationRequested()) {
                return TerminationReason.CANCELLED;
            }
            if (stdout.exceeded()) {
                return TerminationReason.OUTPUT_LIMIT;
            }
            long remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) {
                return TerminationReason.TIMED_OUT;
            }
            try {
                process.waitFor(Math.min(POLL_MILLIS, remainingMillis), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                return TerminationReason.INTERRUPTED;
            }
        }
        return TerminationReason.NATURAL;
    }

    private static boolean drainStreams(
            Thread out,
            Thread err,
            BoundedBytes stdout,
            BoundedBytes stderr) {
        boolean drained = joinWithin(out, STREAM_DRAIN_GRACE)
                && joinWithin(err, STREAM_DRAIN_GRACE);
        if (!drained) {
            stdout.close();
            stderr.close();
            out.interrupt();
            err.interrupt();
            drained = joinWithin(out, STREAM_DRAIN_GRACE)
                    && joinWithin(err, STREAM_DRAIN_GRACE);
        }
        return drained;
    }

    private static boolean joinWithin(Thread thread, Duration maximum) {
        try {
            thread.join(maximum.toMillis());
            return !thread.isAlive();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void terminateProcessTree(Process process) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = root.descendants().toList();
        destroy(descendants, false);
        root.destroy();
        waitForExit(root, descendants, PROCESS_STOP_GRACE);

        List<ProcessHandle> lateDescendants = root.descendants().toList();
        ArrayList<ProcessHandle> allDescendants = new ArrayList<>(descendants);
        allDescendants.addAll(lateDescendants);
        destroy(allDescendants, true);
        if (root.isAlive()) {
            root.destroyForcibly();
        }
        waitForExit(root, allDescendants, PROCESS_STOP_GRACE);
    }

    private static void destroy(List<ProcessHandle> handles, boolean forcibly) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            ProcessHandle handle = handles.get(index);
            if (handle.isAlive()) {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            }
        }
    }

    private static void waitForExit(
            ProcessHandle root,
            List<ProcessHandle> descendants,
            Duration maximum) {
        long deadline = deadlineAfter(maximum);
        while (remainingMillis(deadline) > 0
                && (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            try {
                Thread.sleep(Math.min(POLL_MILLIS, remainingMillis(deadline)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void rejectCancellation(SearchCancellation cancellation)
            throws TextSearchBackend.SearchException {
        if (cancellation.isCancellationRequested()) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_CANCELLED, "ripgrep 搜索已取消"));
        }
    }

    private static void waitBeforeRetry(
            int completedAttempt,
            SearchCancellation cancellation,
            long deadline) throws TextSearchBackend.SearchException {
        long backoffMillis = 25L * completedAttempt;
        long retryDeadline = Math.min(deadline, deadlineAfter(Duration.ofMillis(backoffMillis)));
        while (remainingMillis(retryDeadline) > 0) {
            rejectCancellation(cancellation);
            try {
                Thread.sleep(Math.min(POLL_MILLIS, remainingMillis(retryDeadline)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                rejectInterrupted();
            }
        }
        if (remainingMillis(deadline) <= 0) {
            throw new TextSearchBackend.SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_TIMED_OUT, "ripgrep 搜索超过时间上限"));
        }
    }

    private static void rejectInterrupted() throws TextSearchBackend.SearchException {
        throw new TextSearchBackend.SearchException(ToolError.of(
                ToolErrorCode.OPERATION_TIMED_OUT, "ripgrep 搜索已取消"));
    }

    private static boolean isTransientResourceFailure(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return false;
        }
        String normalized = diagnostic.toLowerCase(Locale.ROOT);
        return normalized.contains("resource temporarily unavailable")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("try again")
                || normalized.contains("eagain")
                || normalized.contains("error=11")
                || normalized.contains("error=8")
                || normalized.contains("error=1450")
                || normalized.contains("error=1455")
                || normalized.contains("not enough memory")
                || normalized.contains("insufficient system resources");
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return 0;
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private enum TerminationReason {
        NATURAL,
        CANCELLED,
        INTERRUPTED,
        TIMED_OUT,
        OUTPUT_LIMIT
    }

    private static final class AttemptFailure extends Exception {
        private final ToolError error;
        private final boolean retryable;

        private AttemptFailure(ToolError error, boolean retryable) {
            super(error.message());
            this.error = Objects.requireNonNull(error, "error 不能为空");
            this.retryable = retryable;
        }

        private ToolError error() {
            return error;
        }

        private boolean retryable() {
            return retryable;
        }
    }

    private static final class BoundedBytes implements Runnable {
        private final InputStream input;
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean exceeded;
        private volatile boolean readFailed;

        private BoundedBytes(InputStream input, int maximum) {
            this.input = input;
            this.maximum = maximum;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int remaining = maximum - bytes.size();
                    if (remaining > 0) {
                        bytes.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        exceeded = true;
                    }
                }
            } catch (IOException exception) {
                readFailed = true;
            }
        }

        boolean exceeded() {
            return exceeded;
        }

        boolean readFailed() {
            return readFailed;
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // 关闭仅用于解除阻塞；错误不会暴露给模型。
            }
        }
    }
}
