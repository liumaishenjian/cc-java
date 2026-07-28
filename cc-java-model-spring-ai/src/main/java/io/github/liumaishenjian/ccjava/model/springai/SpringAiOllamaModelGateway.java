package io.github.liumaishenjian.ccjava.model.springai;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelCallContext;
import io.github.liumaishenjian.ccjava.core.ModelFailureKind;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import reactor.core.publisher.BaseSubscriber;

/**
 * 使用 Spring AI 2.0 与 Ollama 完成恰好一个流式模型回合的 Adapter。
 *
 * <p>Adapter 把 Reactor 信号排入队列，再由调用 {@link #complete} 的 Runtime
 * 线程串行聚合和发布 Delta，因此不会把 Reactor 线程模型泄漏到无锁 Session。
 * 它不调用 ChatClient、ToolCallingAdvisor 或 ToolCallingManager 的执行方法；
 * 所有原始 Tool Call 都只通过完整 {@link ModelTurn} 返回给 Runtime。</p>
 *
 * <p>Spring 内部重试被固定为零次，Provider 尝试次数由 Core 的有界重试策略
 * 统一掌握。取消会释放 Reactor Subscription；公开资料没有保证 Ollama 服务端
 * 同时停止推理，因此这里只声明并测试客户端流边界。</p>
 *
 * @since 0.1.0
 */
public final class SpringAiOllamaModelGateway implements ModelGateway {

    private static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofMillis(100);

    private final StreamingChatModel chatModel;
    private final OllamaModelConfiguration configuration;
    private final SpringAiMessageMapper messageMapper;
    private final Clock clock;
    private final long maxAggregatedUtf8Bytes;
    private final int maxToolCalls;

    /**
     * 使用显式配置创建真实 Ollama Gateway。
     *
     * @param configuration 已校验的 Provider 配置
     * @return 不启用框架自动 Tool Loop 或内部重试的 Gateway
     */
    public static SpringAiOllamaModelGateway create(
            OllamaModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration 不能为空");
        OllamaApi api = OllamaApi.builder()
                .baseUrl(configuration.baseUrl().toString())
                .build();
        OllamaChatOptions defaults = options(configuration, java.util.List.of());
        RetryTemplate noInternalRetry = new RetryTemplate(RetryPolicy.withMaxRetries(0));
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .options(defaults)
                .retryTemplate(noInternalRetry)
                .build();
        return new SpringAiOllamaModelGateway(
                model,
                configuration,
                new SpringAiMessageMapper(),
                Clock.systemUTC(),
                SpringAiStreamAccumulator.DEFAULT_MAX_AGGREGATED_UTF8_BYTES,
                SpringAiStreamAccumulator.DEFAULT_MAX_TOOL_CALLS);
    }

    /**
     * 注入 StreamingChatModel 的测试与边缘 Composition 构造器。
     *
     * @param chatModel Spring AI 单回合流式模型
     * @param configuration Provider 配置
     * @param clock 截止时间来源
     */
    SpringAiOllamaModelGateway(
            StreamingChatModel chatModel,
            OllamaModelConfiguration configuration,
            Clock clock) {
        this(
                chatModel,
                configuration,
                clock,
                SpringAiStreamAccumulator.DEFAULT_MAX_AGGREGATED_UTF8_BYTES,
                SpringAiStreamAccumulator.DEFAULT_MAX_TOOL_CALLS);
    }

    /**
     * 注入本地响应上限的包内测试构造器。
     *
     * @param chatModel Spring AI 单回合流式模型
     * @param configuration Provider 配置
     * @param clock 截止时间来源
     * @param maxAggregatedUtf8Bytes 聚合文本、终止原因与 Tool 字段的 UTF-8 字节上限
     * @param maxToolCalls 不同 Tool Call ID 数量上限
     */
    SpringAiOllamaModelGateway(
            StreamingChatModel chatModel,
            OllamaModelConfiguration configuration,
            Clock clock,
            long maxAggregatedUtf8Bytes,
            int maxToolCalls) {
        this(
                chatModel,
                configuration,
                new SpringAiMessageMapper(),
                clock,
                maxAggregatedUtf8Bytes,
                maxToolCalls);
    }

    private SpringAiOllamaModelGateway(
            StreamingChatModel chatModel,
            OllamaModelConfiguration configuration,
            SpringAiMessageMapper messageMapper,
            Clock clock,
            long maxAggregatedUtf8Bytes,
            int maxToolCalls) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration 不能为空");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxAggregatedUtf8Bytes <= 0) {
            throw new IllegalArgumentException("maxAggregatedUtf8Bytes 必须大于 0");
        }
        if (maxToolCalls <= 0) {
            throw new IllegalArgumentException("maxToolCalls 必须大于 0");
        }
        this.maxAggregatedUtf8Bytes = maxAggregatedUtf8Bytes;
        this.maxToolCalls = maxToolCalls;
    }

    /**
     * 使用无观察、无取消的兼容上下文执行一次完整回合。
     *
     * @param request 项目模型请求
     * @return 聚合完成的模型回合
     * @throws ModelGatewayException Provider 或协议失败时
     */
    @Override
    public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
        return complete(request, ModelCallContext.unbounded());
    }

    /**
     * 在项目取消与截止时间边界内串行排空 Spring AI 流。
     *
     * @param request 项目模型请求
     * @param context 单次 Provider 尝试上下文
     * @return 完整且可进入 Runtime 的模型回合
     * @throws ModelGatewayException 取消、超时、Provider 错误或不完整响应时
     */
    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelCallContext context) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        ensureActive(context);

        Prompt prompt = new Prompt(
                messageMapper.toSpringMessages(request.messages()),
                options(
                        configuration,
                        messageMapper.toToolCallbacks(request.toolDefinitions())));
        SpringAiStreamAccumulator accumulator = new SpringAiStreamAccumulator(
                maxAggregatedUtf8Bytes,
                maxToolCalls);
        QueuedSubscriber subscriber = new QueuedSubscriber();
        chatModel.stream(prompt).subscribe(subscriber);

        CancellationToken token = context.cancellationToken();
        try (CancellationToken.Registration ignored =
                token.onCancellation(subscriber::cancel)) {
            while (true) {
                ensureActive(context);
                StreamSignal signal = pollSignal(subscriber.signals(), context.deadline());
                if (signal == null) {
                    continue;
                }
                // 信号可能恰好在阻塞等待期间到达；处理或发布前必须重新确认边界。
                ensureActive(context);
                if (signal instanceof StreamSignal.Next next) {
                    String delta;
                    try {
                        delta = accumulator.accept(next.response());
                    } catch (SpringAiStreamAccumulator.ModelAggregationException failure) {
                        subscriber.cancel();
                        throw aggregationFailure(
                                failure,
                                accumulator.hasPartialResponse());
                    }
                    if (!delta.isEmpty()) {
                        boolean published = token.runIfActive(
                                () -> context.observer().onTextDelta(delta));
                        if (!published) {
                            subscriber.cancel();
                            throw ModelGatewayException.cancelled("模型流已取消");
                        }
                    }
                    ensureActive(context);
                    subscriber.requestNext();
                    continue;
                }
                if (signal instanceof StreamSignal.Error error) {
                    if (error.failure() instanceof StreamQueueOverflowException) {
                        throw new ModelGatewayException(
                                ModelFailureKind.RESPONSE_LIMIT_EXCEEDED,
                                "模型流信号超过本地安全上限",
                                false,
                                accumulator.hasPartialResponse(),
                                error.failure());
                    }
                    throw SpringAiFailureMapper.map(
                            error.failure(),
                            accumulator.hasPartialResponse());
                }
                if (signal == StreamSignal.Cancelled.INSTANCE) {
                    throw ModelGatewayException.cancelled("模型流已取消");
                }
                try {
                    return accumulator.finish();
                } catch (SpringAiStreamAccumulator.ModelAggregationException failure) {
                    throw aggregationFailure(
                            failure,
                            accumulator.hasPartialResponse());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            subscriber.cancel();
            throw ModelGatewayException.cancelled("等待模型流时线程被中断");
        } finally {
            subscriber.cancel();
        }
    }

    private void ensureActive(ModelCallContext context) throws ModelGatewayException {
        if (context.cancellationToken().isCancellationRequested()) {
            throw ModelGatewayException.cancelled("模型流已取消");
        }
        if (context.deadlineReached(clock)) {
            throw ModelGatewayException.deadlineExceeded("模型请求超过截止时间");
        }
    }

    private StreamSignal pollSignal(
            BlockingQueue<StreamSignal> signals,
            Optional<Instant> deadline) throws InterruptedException, ModelGatewayException {
        Duration wait = CANCELLATION_POLL_INTERVAL;
        if (deadline.isPresent()) {
            Duration remaining = Duration.between(clock.instant(), deadline.orElseThrow());
            if (remaining.isZero() || remaining.isNegative()) {
                throw ModelGatewayException.deadlineExceeded("模型请求超过截止时间");
            }
            if (remaining.compareTo(wait) < 0) {
                wait = remaining;
            }
        }
        return signals.poll(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
    }

    private static ModelGatewayException aggregationFailure(
            SpringAiStreamAccumulator.ModelAggregationException failure,
            boolean partial) {
        return switch (failure.kind()) {
            case INCOMPLETE_RESPONSE -> new ModelGatewayException(
                    ModelFailureKind.INCOMPLETE_RESPONSE,
                    "Provider 流未完整结束",
                    false,
                    partial,
                    failure);
            case INVALID_RESPONSE -> new ModelGatewayException(
                    ModelFailureKind.INVALID_RESPONSE,
                    "Provider 返回了无效的模型响应",
                    false,
                    partial,
                    failure);
            case RESPONSE_LIMIT_EXCEEDED -> new ModelGatewayException(
                    ModelFailureKind.RESPONSE_LIMIT_EXCEEDED,
                    "模型响应超过本地安全上限",
                    false,
                    partial,
                    failure);
        };
    }

    private static OllamaChatOptions options(
            OllamaModelConfiguration configuration,
            java.util.List<org.springframework.ai.tool.ToolCallback> callbacks) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(configuration.model())
                .numPredict(configuration.maxOutputTokens())
                .temperature(configuration.temperature())
                .toolCallbacks(callbacks);
        if (configuration.thinkingEnabled()) {
            builder.enableThinking();
        } else {
            builder.disableThinking();
        }
        return builder.build();
    }

    private sealed interface StreamSignal {

        record Next(ChatResponse response) implements StreamSignal {

            public Next {
                Objects.requireNonNull(response, "response 不能为空");
            }
        }

        record Error(Throwable failure) implements StreamSignal {

            public Error {
                Objects.requireNonNull(failure, "failure 不能为空");
            }
        }

        enum Complete implements StreamSignal {
            INSTANCE
        }

        enum Cancelled implements StreamSignal {
            INSTANCE
        }
    }

    /**
     * Reactor 线程只向有界队列写入，Runtime 调用线程负责协议观察与聚合。
     *
     * <p>Subscriber 每次只请求一个响应 Chunk；容量二允许一个 Chunk 与随后
     * 到达的终止信号共存。处理线程成功校验并发布当前 Chunk 后才请求下一项，
     * 从而不会让 Provider 输出在堆内无界排队。</p>
     */
    private static final class QueuedSubscriber extends BaseSubscriber<ChatResponse> {

        private static final int SIGNAL_QUEUE_CAPACITY = 2;

        private final BlockingQueue<StreamSignal> signals =
                new ArrayBlockingQueue<>(SIGNAL_QUEUE_CAPACITY);

        BlockingQueue<StreamSignal> signals() {
            return signals;
        }

        /**
         * 通知上游当前 Chunk 已被消费，可以发送下一项。
         */
        void requestNext() {
            request(1);
        }

        @Override
        protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            request(1);
        }

        @Override
        protected void hookOnNext(ChatResponse response) {
            enqueue(new StreamSignal.Next(response));
        }

        @Override
        protected void hookOnComplete() {
            enqueue(StreamSignal.Complete.INSTANCE);
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            enqueue(new StreamSignal.Error(throwable));
        }

        @Override
        protected void hookOnCancel() {
            enqueue(StreamSignal.Cancelled.INSTANCE);
        }

        private void enqueue(StreamSignal signal) {
            if (signals.offer(signal)) {
                return;
            }
            if (signal == StreamSignal.Cancelled.INSTANCE) {
                signals.clear();
                signals.offer(StreamSignal.Cancelled.INSTANCE);
                return;
            }
            cancel();
            signals.clear();
            signals.offer(new StreamSignal.Error(new StreamQueueOverflowException()));
        }
    }

    /**
     * 表示非合规 Publisher 绕过逐项 Demand 并填满本地信号队列。
     */
    private static final class StreamQueueOverflowException extends RuntimeException {
    }
}
