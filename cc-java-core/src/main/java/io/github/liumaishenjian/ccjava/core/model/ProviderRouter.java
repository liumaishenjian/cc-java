package io.github.liumaishenjian.ccjava.core.model;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.model.ModelCapability;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 在多个 Provider 间执行共享预算与保守 Fallback 的模型 Gateway。
 *
 * <p>请求含 Tool definitions 时必须路由到 TOOL_CALLING candidate；Router 会优先使用 streaming
 * Gateway，并在普通 Gateway 返回前后检查取消。所有候选共享 deadline、CancellationToken、最大
 * attempt 与可选成本单位。只有 attempt 没有发布
 * visible delta 且异常明确可重试时才可切换。现有 ModelGateway 只能在成功返回时暴露 durable
 * Assistant/Tool intent，因此 Router 无法观察“Provider 已 durable 但尚未返回”的内部状态；这种
 * 不确定失败必须由 Adapter 分类成非 retryable，文档不再宣称 Router 能直接证明该内部状态。</p>
 *
 * @since 0.1.0
 */
public final class ProviderRouter implements StreamingModelGateway {
    private final List<ModelProviderRoute> routes;
    private final ProviderRoutePolicy policy;

    /**
     * 使用默认共享预算创建按稳定顺序尝试的路由器。
     *
     * @param routes 按尝试优先级排列的 Provider routes
     */
    public ProviderRouter(List<ModelProviderRoute> routes) {
        this(routes, ProviderRoutePolicy.defaults());
    }

    /**
     * 使用显式共享预算创建路由器。
     *
     * @param routes 按尝试优先级排列的 Provider routes
     * @param policy 单次 complete 共享的 attempt/deadline/cost 策略
     */
    public ProviderRouter(List<ModelProviderRoute> routes, ProviderRoutePolicy policy) {
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes 不能为空"));
        if (this.routes.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 Provider route");
        }
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
        Set<ModelCapability> required = requirements(request);
        Instant requestDeadline = policy.deadlineForRequest();
        int attempts = 0;
        long costUnits = 0;
        ModelGatewayException last = null;
        for (int index = 0; index < routes.size(); index++) {
            ModelProviderRoute route = routes.get(index);
            if (!supports(route, required)) {
                continue;
            }
            if (cancelled(cancellation) || deadlineReached(requestDeadline)) {
                throw cancelledOrDeadline(cancellation);
            }
            if (attempts >= policy.maxAttempts()
                    || exceedsCost(costUnits, policy.attemptCostUnits())) {
                break;
            }
            attempts++;
            costUnits += policy.attemptCostUnits();
            AtomicBoolean visible = new AtomicBoolean();
            try {
                ModelTurn turn;
                if (route.gateway() instanceof StreamingModelGateway streaming) {
                    turn = streaming.complete(request, delta -> {
                        if (cancelled(cancellation) || deadlineReached(requestDeadline)) {
                            return;
                        }
                        visible.set(true);
                        observer.onTextDelta(delta);
                    }, cancellation);
                } else {
                    turn = route.gateway().complete(request);
                    if (cancelled(cancellation) || deadlineReached(requestDeadline)) {
                        throw cancelledOrDeadline(cancellation);
                    }
                }
                // 返回的 Assistant/Tool intent 已 durable；成功后永不 fallback。
                return turn;
            } catch (ModelGatewayException failure) {
                last = failure;
                if (visible.get() || !canFallback(failure) || index + 1 == routes.size()) {
                    throw failure;
                }
                Duration requestedDelay = failure.retryAfter().orElse(Duration.ZERO);
                waitRetryDelay(requestedDelay, requestDeadline, cancellation);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ModelGatewayException(
                ModelGatewayException.FailureKind.PERMANENT,
                "No configured provider can serve the model request");
    }

    private Set<ModelCapability> requirements(ModelRequest request) {
        EnumSet<ModelCapability> required = EnumSet.of(ModelCapability.TEXT);
        if (!request.toolDefinitions().isEmpty()) {
            required.add(ModelCapability.TOOL_CALLING);
        }
        return Set.copyOf(required);
    }

    private static boolean supports(ModelProviderRoute route, Set<ModelCapability> required) {
        return required.stream().allMatch(route.capabilities()::supports);
    }

    private boolean exceedsCost(long spent, long next) {
        return policy.maxCostUnits() >= 0 && spent + next > policy.maxCostUnits();
    }

    private boolean deadlineReached(Instant requestDeadline) {
        return !policy.clock().instant().isBefore(requestDeadline);
    }

    private static boolean cancelled(CancellationToken cancellation) {
        return cancellation.isCancellationRequested();
    }

    private ModelGatewayException cancelledOrDeadline(CancellationToken cancellation) {
        return new ModelGatewayException(
                ModelGatewayException.FailureKind.CANCELLED,
                cancelled(cancellation) ? "Model route cancelled" : "Model route deadline reached");
    }

    private void waitRetryDelay(
            Duration requested,
            Instant requestDeadline,
            CancellationToken cancellation) throws ModelGatewayException {
        Duration bounded = requested.compareTo(policy.maxRetryDelay()) > 0
                ? policy.maxRetryDelay() : requested;
        Duration deadlineRemaining = Duration.between(policy.clock().instant(), requestDeadline);
        if (deadlineRemaining.isNegative() || deadlineRemaining.isZero()) {
            throw cancelledOrDeadline(cancellation);
        }
        if (bounded.compareTo(deadlineRemaining) > 0) {
            bounded = deadlineRemaining;
        }
        long remaining = bounded.toNanos();
        while (remaining > 0) {
            if (cancelled(cancellation) || deadlineReached(requestDeadline)) {
                throw cancelledOrDeadline(cancellation);
            }
            long slice = Math.min(remaining, Duration.ofMillis(10).toNanos());
            LockSupport.parkNanos(slice);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayException(
                        ModelGatewayException.FailureKind.CANCELLED, "Model route interrupted");
            }
            remaining -= slice;
        }
    }

    private static boolean canFallback(ModelGatewayException failure) {
        return failure.kind() == ModelGatewayException.FailureKind.RETRYABLE
                || failure.kind() == ModelGatewayException.FailureKind.RETRY_EXHAUSTED;
    }
}
