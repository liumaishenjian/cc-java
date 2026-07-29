package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.CANCELLED;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.INCOMPLETE_STREAM;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE;
import static io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRY_EXHAUSTED;

/**
 * 为单个模型回合提供可取消、禁止重复 Delta 的有界重试。
 *
 * <p>只有在当前尝试尚未发布任何文本且 Adapter 明确分类为
 * {@link ModelGatewayException.FailureKind#RETRYABLE} 时才重试。已经发布 Delta
 * 后失败会转换为不完整流，防止下一次尝试把同一内容再次展示给用户。</p>
 *
 * @since 0.1.0
 */
public final class RetryingModelGateway implements StreamingModelGateway {

    private final ModelGateway delegate;
    private final ModelRetryPolicy policy;

    /**
     * 创建模型 Gateway 重试装饰器。
     *
     * @param delegate 实际 Provider Gateway
     * @param policy 有界重试策略
     */
    public RetryingModelGateway(
            ModelGateway delegate,
            ModelRetryPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
    }

    @Override
    public ModelTurn complete(
            ModelRequest request,
            ModelStreamObserver observer,
            CancellationToken cancellation) throws ModelGatewayException {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(observer, "observer 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");

        ModelGatewayException lastFailure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            throwIfCancelled(cancellation);
            AtomicBoolean emittedText = new AtomicBoolean();
            try {
                if (delegate instanceof StreamingModelGateway streaming) {
                    return streaming.complete(
                            request,
                            delta -> {
                                emittedText.set(true);
                                observer.onTextDelta(delta);
                            },
                            cancellation);
                }
                return delegate.complete(request);
            } catch (ModelGatewayException failure) {
                if (cancellation.isCancellationRequested()
                        || failure.kind() == CANCELLED) {
                    throw cancelled(failure);
                }
                if (emittedText.get()) {
                    throw new ModelGatewayException(
                            INCOMPLETE_STREAM,
                            "Model stream failed after publishing output",
                            failure);
                }
                if (failure.kind() != RETRYABLE) {
                    throw failure;
                }
                lastFailure = failure;
                if (attempt == policy.maxAttempts()) {
                    throw new ModelGatewayException(
                            RETRY_EXHAUSTED,
                            "Model retry attempts exhausted",
                            failure);
                }
                await(policy.delayAfter(attempt), cancellation);
            }
        }
        throw new ModelGatewayException(
                RETRY_EXHAUSTED,
                "Model retry attempts exhausted",
                lastFailure);
    }

    private static void await(
            Duration delay,
            CancellationToken cancellation) throws ModelGatewayException {
        if (delay.isZero()) {
            throwIfCancelled(cancellation);
            return;
        }
        CountDownLatch cancelled = new CountDownLatch(1);
        try (CancellationToken.Registration ignored =
                     cancellation.onCancellation(cancelled::countDown)) {
            try {
                boolean signalled = cancelled.await(
                        delay.toNanos(),
                        TimeUnit.NANOSECONDS);
                if (signalled || cancellation.isCancellationRequested()) {
                    throw cancelled(null);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayException(
                        CANCELLED,
                        "Model retry wait interrupted",
                        interrupted);
            }
        }
    }

    private static void throwIfCancelled(
            CancellationToken cancellation) throws ModelGatewayException {
        if (cancellation.isCancellationRequested()) {
            throw cancelled(null);
        }
    }

    private static ModelGatewayException cancelled(Throwable cause) {
        return new ModelGatewayException(
                CANCELLED,
                "Model request cancelled",
                cause);
    }
}
