package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.AgentEventEnvelope;
import io.github.liumaishenjian.ccjava.domain.AgentRunResult;
import io.github.liumaishenjian.ccjava.domain.ModelTextDelta;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class CliTestFixtures {

    private CliTestFixtures() {
    }

    static CliDefaults defaults(Path workspace, boolean secretRequired) {
        return new CliDefaults(
                "test-provider",
                "TEST_PROVIDER_API_KEY",
                secretRequired,
                Duration.ofSeconds(30),
                1,
                workspace,
                "测试系统指令",
                URI.create("http://localhost:11434"),
                4_096);
    }

    static CliEnvironment environment(Map<String, String> values) {
        Map<String, String> mutable = new HashMap<>(values);
        mutable.putIfAbsent(CliConfigurationResolver.MODEL_ENV, "test-model");
        Map<String, String> copy = Map.copyOf(mutable);
        return name -> Optional.ofNullable(copy.get(name));
    }

    static CliEnvironment environmentWithoutModel(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name));
    }

    @FunctionalInterface
    interface RunBehavior {

        AgentRunResult run(
                int ordinal,
                String prompt,
                CancellationToken cancellation,
                CliEventListener listener);
    }

    static final class RecordingRuntimeFactory implements CliRuntimeFactory {

        private final Deque<RunBehavior> behaviors;
        private final List<CliConfiguration> configurations = new ArrayList<>();
        private RecordingRuntime runtime;

        RecordingRuntimeFactory(RunBehavior... behaviors) {
            this.behaviors = new ArrayDeque<>(List.of(behaviors));
        }

        @Override
        public CliRuntime create(
                CliConfiguration configuration,
                CliEnvironment environment,
                CliEventListener listener) {
            configurations.add(configuration);
            runtime = new RecordingRuntime(listener, behaviors);
            return runtime;
        }

        RecordingRuntime runtime() {
            return runtime;
        }

        List<CliConfiguration> configurations() {
            return List.copyOf(configurations);
        }
    }

    static final class RecordingRuntime implements CliRuntime {

        private final SessionId sessionId = new SessionId("cli-session");
        private final CliEventListener listener;
        private final Deque<RunBehavior> behaviors;
        private final List<String> prompts = new ArrayList<>();
        private int runOrdinal;
        private boolean closed;

        RecordingRuntime(
                CliEventListener listener,
                Deque<RunBehavior> behaviors) {
            this.listener = Objects.requireNonNull(listener, "listener 不能为空");
            this.behaviors = behaviors;
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public AgentRunResult run(
                String userMessage,
                CancellationToken cancellation) {
            prompts.add(userMessage);
            runOrdinal++;
            RunBehavior behavior = behaviors.pollFirst();
            if (behavior == null) {
                return AgentRunResult.completed(
                        sessionId,
                        new RunId("run-" + runOrdinal),
                        "answer-" + runOrdinal,
                        1,
                        0);
            }
            return behavior.run(
                    runOrdinal,
                    userMessage,
                    cancellation,
                    listener);
        }

        @Override
        public void close() {
            closed = true;
        }

        List<String> prompts() {
            return List.copyOf(prompts);
        }

        boolean closed() {
            return closed;
        }
    }

    static void publishDelta(
            CliEventListener listener,
            int runOrdinal,
            int turnNumber,
            String text) {
        listener.onAgentEvent(new AgentEventEnvelope(
                runOrdinal,
                Instant.parse("2026-07-28T00:00:00Z"),
                new SessionId("cli-session"),
                Optional.of(new RunId("run-" + runOrdinal)),
                new ModelTextDelta(turnNumber, text)));
    }

    static final class ScriptedTerminal implements CliTerminal {

        enum Signal {
            USER_INTERRUPT,
            END_OF_INPUT
        }

        private final boolean interactive;
        private final boolean ansiSupported;
        private final Deque<Object> input;
        private final StringWriter output = new StringWriter();
        private final PrintWriter writer = new PrintWriter(output, true);
        private final AtomicReference<Runnable> interruptHandler = new AtomicReference<>();
        private final AtomicInteger readCount = new AtomicInteger();
        private boolean closed;

        ScriptedTerminal(
                boolean interactive,
                boolean ansiSupported,
                Object... input) {
            this.interactive = interactive;
            this.ansiSupported = ansiSupported;
            this.input = new ArrayDeque<>(List.of(input));
        }

        @Override
        public boolean interactive() {
            return interactive;
        }

        @Override
        public boolean ansiSupported() {
            return ansiSupported;
        }

        @Override
        public String readLine(String prompt)
                throws UserInterruptException, EndOfInputException {
            readCount.incrementAndGet();
            Object next = input.pollFirst();
            if (next == null || next == Signal.END_OF_INPUT) {
                throw new EndOfInputException();
            }
            if (next == Signal.USER_INTERRUPT) {
                throw new UserInterruptException();
            }
            return (String) next;
        }

        @Override
        public InterruptRegistration onInterrupt(Runnable handler) {
            Runnable previous = interruptHandler.getAndSet(handler);
            return () -> interruptHandler.set(previous);
        }

        @Override
        public PrintWriter writer() {
            return writer;
        }

        @Override
        public void close() {
            closed = true;
        }

        void triggerInterrupt() {
            Runnable handler = interruptHandler.get();
            if (handler == null) {
                throw new AssertionError("当前没有活动 Run 中断处理器");
            }
            handler.run();
        }

        String output() {
            writer.flush();
            return output.toString();
        }

        int readCount() {
            return readCount.get();
        }

        boolean hasInterruptHandler() {
            return interruptHandler.get() != null;
        }

        boolean closed() {
            return closed;
        }
    }

    static final class RecordingTerminalFactory implements CliTerminalFactory {

        private final CliTerminal terminal;
        private final CliStartupException failure;
        private int opens;

        RecordingTerminalFactory(CliTerminal terminal) {
            this.terminal = terminal;
            this.failure = null;
        }

        RecordingTerminalFactory(CliStartupException failure) {
            this.terminal = null;
            this.failure = failure;
        }

        @Override
        public CliTerminal open(boolean noColor) throws CliStartupException {
            opens++;
            if (failure != null) {
                throw failure;
            }
            return terminal;
        }

        int opens() {
            return opens;
        }
    }
}
