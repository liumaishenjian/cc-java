package io.github.liumaishenjian.ccjava.model.springai;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.domain.ModelFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ModelFailureSummary;
import io.github.liumaishenjian.ccjava.domain.ModelHttpStatusClass;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.ModelFinishReason;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.ModelUsage;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.Disposable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.PERMANENT;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE;

/**
 * 使用 Spring AI 2.0 执行一个 OpenAI-compatible 流式模型回合。
 *
 * <p>该 Adapter 只转换消息、发布文本增量并聚合最终 Tool Call；它不执行 Tool、
 * 不追加 Session 历史，也不驱动后续 Agent Loop。Reactor 与 Spring AI 类型不会
 * 穿过本类进入 Domain/Core。</p>
 *
 * @since 0.1.0
 */
public final class SpringAiModelGateway implements StreamingModelGateway {

    private final ChatModel chatModel;
    private final String model;
    private final SpringAiPromptMapper promptMapper;

    /**
     * 创建真实或测试用 Spring AI Adapter。
     *
     * @param chatModel Spring AI ChatModel；生产使用 OpenAiChatModel
     * @param model 每次请求显式指定的模型名
     */
    public SpringAiModelGateway(ChatModel chatModel, String model) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.model = requireText(model, "model");
        this.promptMapper = new SpringAiPromptMapper();
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(observer, "observer 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (cancellation.isCancellationRequested()) {
            throw new ModelGatewayException(CANCELLED, "Model request cancelled");
        }

        AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean receivedResponse = new AtomicBoolean();
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        var rawResponses = chatModel.stream(promptMapper.map(request, model))
                .doOnNext(response -> receivedResponse.set(true));
        var responses = new MessageAggregator().aggregate(
                rawResponses,
                aggregate::set);
        try (CancellationToken.Registration ignored = cancellation.onCancellation(() -> {
            cancelled.set(true);
            Disposable disposable = subscription.get();
            if (disposable != null) {
                disposable.dispose();
            }
            terminal.completeExceptionally(new ModelCancelledException());
        })) {
            Disposable disposable = responses.subscribe(
                    response -> publishDelta(response, observer),
                    terminal::completeExceptionally,
                    () -> terminal.complete(null));
            subscription.set(disposable);
            if (cancelled.get()) {
                disposable.dispose();
            }
            terminal.join();
        } catch (CompletionException exception) {
            if (cancelled.get() || exception.getCause() instanceof ModelCancelledException) {
                throw new ModelGatewayException(CANCELLED, "Model request cancelled");
            }
            Throwable cause = unwrap(exception);
            FailureClassification classification = receivedResponse.get()
                    ? incompleteStream()
                    : classify(cause);
            throw new ModelGatewayException(
                    classification.kind(),
                    classification.kind() == INCOMPLETE_STREAM
                            ? "OpenAI-compatible model stream ended before a complete response"
                            : "OpenAI-compatible model request failed: " + safeTypeName(cause),
                    classification.summary(),
                    cause);
        }

        ChatResponse response = aggregate.get();
        if (response == null || response.getResult() == null) {
            throw invalidResponse(
                    "Provider returned an incomplete model stream",
                    receivedResponse.get());
        }
        return mapTurn(response, receivedResponse.get());
    }

    private static boolean hasProviderOutput(ChatResponse response) {
        if (response == null) {
            return false;
        }
        Generation result = response.getResult();
        if (result == null || result.getOutput() == null) {
            return false;
        }
        var output = result.getOutput();
        String text = output.getText();
        return (text != null && !text.isEmpty()) || !output.getToolCalls().isEmpty();
    }

    private static void publishDelta(ChatResponse response, ModelStreamObserver observer) {
        if (!hasProviderOutput(response)) {
            return;
        }
        Generation result = response.getResult();
        String text = result.getOutput().getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            observer.onTextDelta(text);
        } catch (RuntimeException ignored) {
            // 观察者故障不能改变模型回合。
        }
    }

    private static ModelTurn mapTurn(
            ChatResponse response,
            boolean receivedOutput) throws ModelGatewayException {
        Generation result = response.getResult();
        org.springframework.ai.chat.messages.AssistantMessage output = result.getOutput();
        if (output == null) {
            throw invalidResponse(
                    "Provider returned a model response without output",
                    receivedOutput);
        }
        List<ToolCall> calls;
        try {
            calls = output.getToolCalls().stream()
                    .map(SpringAiModelGateway::mapToolCall)
                    .toList();
        } catch (ToolArgumentsMappingException exception) {
            throw (ModelGatewayException) exception.getCause();
        }
        AssistantMessage assistant = new AssistantMessage(
                Objects.requireNonNullElse(output.getText(), ""),
                calls);

        Usage springUsage = response.getMetadata().getUsage();
        Optional<ModelUsage> usage = mapUsage(springUsage);
        String providerModel = response.getMetadata().getModel();
        ModelTurnMetadata metadata = new ModelTurnMetadata(
                mapFinishReason(result.getMetadata().getFinishReason()),
                usage,
                Optional.ofNullable(providerModel));
        validateCompleteTurn(assistant, metadata.finishReason(), receivedOutput);
        return new ModelTurn(assistant, metadata);
    }

    private static void validateCompleteTurn(
            AssistantMessage assistant,
            ModelFinishReason finishReason,
            boolean receivedOutput) throws ModelGatewayException {
        if (finishReason == ModelFinishReason.UNKNOWN
                || finishReason == ModelFinishReason.OTHER) {
            throw invalidResponse(
                    "Provider stream ended without a supported finish reason",
                    receivedOutput);
        }
        boolean hasToolCalls = !assistant.toolCalls().isEmpty();
        if (hasToolCalls != (finishReason == ModelFinishReason.TOOL_CALLS)) {
            throw invalidResponse(
                    "Provider returned inconsistent Tool Call completion metadata",
                    receivedOutput);
        }
    }

    private static ToolCall mapToolCall(
            org.springframework.ai.chat.messages.AssistantMessage.ToolCall call) {
        try {
            return new ToolCall(
                    call.id(),
                    call.name(),
                    SpringAiJson.readArguments(call.arguments()));
        } catch (ModelGatewayException exception) {
            throw new ToolArgumentsMappingException(exception);
        }
    }

    private static Optional<ModelUsage> mapUsage(Usage usage) {
        if (usage == null) {
            return Optional.empty();
        }
        int input = nonNegative(usage.getPromptTokens());
        int output = nonNegative(usage.getCompletionTokens());
        int total = nonNegative(usage.getTotalTokens());
        if (input == 0 && output == 0 && total == 0) {
            return Optional.empty();
        }
        return Optional.of(new ModelUsage(input, output, Math.max(total, input + output)));
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static ModelFinishReason mapFinishReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ModelFinishReason.UNKNOWN;
        }
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "stop" -> ModelFinishReason.STOP;
            case "tool_calls", "tool_call" -> ModelFinishReason.TOOL_CALLS;
            case "length" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.OTHER;
        };
    }

    private static String safeTypeName(Throwable throwable) {
        return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static FailureClassification classify(Throwable throwable) {
        for (Throwable current = throwable;
                current != null;
                current = current.getCause()) {
            if (current instanceof OpenAIServiceException service) {
                return classifyStatus(service.statusCode());
            }
            if (current instanceof java.util.concurrent.TimeoutException) {
                return retryable(ModelFailureCategory.REQUEST_TIMEOUT, Optional.empty());
            }
            if (current instanceof OpenAIRetryableException
                    || current instanceof OpenAIIoException
                    || current instanceof java.io.IOException) {
                return retryable(ModelFailureCategory.NETWORK_ERROR, Optional.empty());
            }
        }
        return permanent(ModelFailureCategory.PROVIDER_ERROR, Optional.empty());
    }

    private static FailureClassification classifyStatus(int status) {
        Optional<ModelHttpStatusClass> statusClass = status >= 500
                ? Optional.of(ModelHttpStatusClass.SERVER_ERROR)
                : Optional.of(ModelHttpStatusClass.CLIENT_ERROR);
        if (status >= 500) {
            return retryable(ModelFailureCategory.PROVIDER_UNAVAILABLE, statusClass);
        }
        return switch (status) {
            case 408 -> retryable(ModelFailureCategory.REQUEST_TIMEOUT, statusClass);
            case 409 -> retryable(ModelFailureCategory.REQUEST_CONFLICT, statusClass);
            case 429 -> retryable(ModelFailureCategory.RATE_LIMITED, statusClass);
            case 401, 403 -> permanent(ModelFailureCategory.AUTHENTICATION_FAILED, statusClass);
            default -> permanent(ModelFailureCategory.INVALID_REQUEST, statusClass);
        };
    }

    private static FailureClassification retryable(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass) {
        return new FailureClassification(
                RETRYABLE,
                ModelFailureSummary.firstAttempt(category, statusClass, false));
    }

    private static FailureClassification permanent(
            ModelFailureCategory category,
            Optional<ModelHttpStatusClass> statusClass) {
        return new FailureClassification(
                PERMANENT,
                ModelFailureSummary.firstAttempt(category, statusClass, false));
    }

    private static FailureClassification incompleteStream() {
        return new FailureClassification(
                INCOMPLETE_STREAM,
                ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.INCOMPLETE_STREAM,
                        Optional.empty(),
                        true));
    }

    private static ModelGatewayException invalidResponse(
            String message,
            boolean receivedOutput) {
        return new ModelGatewayException(
                INCOMPLETE_STREAM,
                message,
                ModelFailureSummary.firstAttempt(
                        ModelFailureCategory.INVALID_RESPONSE,
                        Optional.empty(),
                        receivedOutput));
    }

    private record FailureClassification(
            ModelGatewayException.FailureKind kind,
            ModelFailureSummary summary) {
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }

    private static final class ModelCancelledException extends RuntimeException {
    }

    private static final class ToolArgumentsMappingException extends RuntimeException {
        private ToolArgumentsMappingException(ModelGatewayException cause) {
            super(cause);
        }
    }
}
