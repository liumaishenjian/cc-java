package io.github.liumaishenjian.ccjava.core.hook;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.hook.HookAggregateResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookDisposition;
import io.github.liumaishenjian.ccjava.domain.hook.HookEventKind;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionResult;
import io.github.liumaishenjian.ccjava.domain.hook.HookExecutionStatus;
import io.github.liumaishenjian.ccjava.domain.hook.HookFailurePolicy;
import io.github.liumaishenjian.ccjava.domain.hook.HookInvocation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在 Core 决策点同步收敛多个匹配 Hook。
 *
 * <p>匹配器只读，Handler 可以并发执行，但结果始终按绑定顺序重排；阻断/否决
 * 优先于允许，超时和异常按每个绑定的失败策略转换。这个协调器不属于
 * {@link io.github.liumaishenjian.ccjava.core.LifecycleDispatcher}：后者仍然是
 * 不可阻断的观察旁路。</p>
 *
 * @since 0.1.0
 */
public final class HookCoordinator {

    private static final HookCoordinator DISABLED = new HookCoordinator(List.of(), null, Duration.ZERO);

    private final List<HookBinding> bindings;
    private final ConcurrentMap<io.github.liumaishenjian.ccjava.domain.RunId,
            ConcurrentMap<Long, List<HookBinding>>> runBindings = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong runBindingSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private final ExecutorService executor;
    private final Duration timeout;
    private final ConcurrentMap<io.github.liumaishenjian.ccjava.domain.RunId, String> pendingContext =
            new ConcurrentHashMap<>();
    private static final int MAX_PENDING_RUNS = 64;

    /**
     * 创建 Hook 协调器。
     *
     * @param bindings 已装载的绑定，顺序用于稳定聚合
     * @param executor 有界 Handler 执行器；没有绑定时可以为 {@code null}
     * @param timeout 一次生命周期点的总墙钟上限
     */
    public HookCoordinator(
            List<HookBinding> bindings,
            ExecutorService executor,
            Duration timeout) {
        this.bindings = sortBindings(bindings);
        this.executor = executor;
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        if (!timeout.isZero() && timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        if (executor != null && timeout.toMillis() < 1) {
            throw new IllegalArgumentException("启用 Handler 时 timeout 必须大于 0");
        }
    }

    /**
     * 返回无绑定的观察空实现，供未启用 S09 Hook 的旧装配路径使用。
     *
     * @return 不执行任何 Handler 的共享协调器
     */
    public static HookCoordinator disabled() {
        return DISABLED;
    }

    /**
     * 执行一个生命周期点的匹配 Handler。
     *
     * @param invocation 生命周期请求
     * @param cancellationToken 当前 Run 取消信号
     * @return 按绑定顺序聚合的结果
     */
    public HookAggregateResult evaluate(
            HookInvocation invocation,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(cancellationToken, "cancellationToken 不能为空");
        List<HookBinding> candidates = new ArrayList<>(bindings);
        invocation.runId().map(runBindings::get).ifPresent(scoped -> scoped.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> candidates.addAll(entry.getValue())));
        List<HookBinding> matching = sortBindings(candidates).stream()
                .filter(binding -> binding.matcher().matches(invocation))
                .toList();
        if (matching.isEmpty()) {
            return HookAggregateResult.empty(invocation.event());
        }
        if (cancellationToken.isCancellationRequested()) {
            return aggregate(invocation.event(), matching.stream()
                    .map(binding -> failureResult(binding, HookExecutionStatus.CANCELLED, "Run 已取消"))
                    .toList());
        }

        List<Callable<HookExecutionResult>> tasks = matching.stream()
                .map(binding -> (Callable<HookExecutionResult>) () -> invoke(binding, invocation, cancellationToken))
                .toList();
        List<HookExecutionResult> results = new ArrayList<>(matching.size());
        try {
            List<java.util.concurrent.Future<HookExecutionResult>> futures = executor.invokeAll(
                    tasks,
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS);
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index);
                HookBinding binding = matching.get(index);
                if (future.isCancelled()) {
                    results.add(failureResult(binding, HookExecutionStatus.TIMED_OUT, "Hook 超时"));
                } else {
                    try {
                        results.add(Objects.requireNonNull(
                                future.get(),
                                "Hook Handler 返回 null"));
                    } catch (Exception exception) {
                        results.add(failureResult(binding, HookExecutionStatus.FAILED, "Hook 执行失败"));
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            for (HookBinding binding : matching) {
                results.add(failureResult(binding, HookExecutionStatus.CANCELLED, "Hook 协调被中断"));
            }
        } catch (RuntimeException exception) {
            for (HookBinding binding : matching) {
                results.add(failureResult(binding, HookExecutionStatus.FAILED, "Hook 执行器不可用"));
            }
        }
        return aggregate(invocation.event(), results);
    }

    /**
     * 为当前 Run 原子绑定已通过宿主 trust Gate 的 Skill/Plugin Hooks。
     *
     * <p>该 seam 不修改全局绑定；同一 Run 可以按调用顺序持有多个彼此独立的 lease，关闭任一 lease
     * 只移除自身 bindings。调用者必须持有返回 lease 到唯一 Run 终态。Resume/Fork 没有活动 Run，
     * 因此不会调用本方法。Handler/Executor 仍复用当前 S09 协调器，不改变聚合优先级或失败策略。</p>
     *
     * @param runId 当前活动 Run
     * @param scopedBindings 已由宿主验证 trust 与来源的绑定
     * @return 幂等解绑 lease
     */
    public AutoCloseable bindRun(io.github.liumaishenjian.ccjava.domain.RunId runId,
            List<HookBinding> scopedBindings) {
        Objects.requireNonNull(runId, "runId 不能为空");
        List<HookBinding> checked = sortBindings(scopedBindings);
        if (checked.stream().anyMatch(binding -> !binding.trusted())) {
            throw new IllegalArgumentException("Run scoped Hook 必须已通过 trust Gate");
        }
        if (checked.isEmpty()) return () -> { };
        if (executor == null) {
            throw new IllegalStateException("HookCoordinator 没有可用执行器");
        }
        long leaseId = runBindingSequence.incrementAndGet();
        ConcurrentMap<Long, List<HookBinding>> leases = runBindings.computeIfAbsent(
                runId, ignored -> new ConcurrentHashMap<>());
        leases.put(leaseId, checked);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            ConcurrentMap<Long, List<HookBinding>> current = runBindings.get(runId);
            if (current == null) return;
            current.remove(leaseId, checked);
            if (current.isEmpty()) runBindings.remove(runId, current);
        };
    }

    /**
     * 返回当前 Run 的 scoped Hook 数量，仅供安全生命周期测试与诊断。
     *
     * @param runId 要检查的 Run identity
     * @return 当前仍绑定到该 Run 的 Hook 数量
     */
    public int runBindingCount(io.github.liumaishenjian.ccjava.domain.RunId runId) {
        ConcurrentMap<Long, List<HookBinding>> scoped = runBindings.get(
                Objects.requireNonNull(runId, "runId 不能为空"));
        return scoped == null ? 0 : scoped.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 保存同一 Run 下一模型回合一次性消费的非规范 Hook Context。
     *
     * @param runId Context 所属的当前 Run
     * @param context 已有界的非可信 Hook Context
     */
    public void recordTransientContext(
            io.github.liumaishenjian.ccjava.domain.RunId runId,
            String context) {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        if (context.isBlank() || (!pendingContext.containsKey(runId) && pendingContext.size() >= MAX_PENDING_RUNS)) {
            return;
        }
        pendingContext.merge(runId, context, (existing, added) -> {
            String combined = existing + '\n' + added;
            int maximum = HookAggregateResult.MAX_CONTEXT_CHARACTERS;
            return combined.codePointCount(0, combined.length()) <= maximum
                    ? combined
                    : combined.substring(0, combined.offsetByCodePoints(0, maximum));
        });
    }

    /**
     * 将 pending Hook Context 作为不可信 System 投影追加一次并立即清除。
     *
     * @param request 尚未发送给 Provider 的模型请求
     * @return 附加一次性 Context 的新请求；没有 pending Context 时返回原请求
     */
    public io.github.liumaishenjian.ccjava.domain.ModelRequest projectTransientContext(
            io.github.liumaishenjian.ccjava.domain.ModelRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String context = pendingContext.remove(request.runId());
        if (context == null) {
            return request;
        }
        List<io.github.liumaishenjian.ccjava.domain.AgentMessage> messages = new ArrayList<>(request.messages());
        messages.add(new io.github.liumaishenjian.ccjava.domain.SystemMessage(
                "<hook-context trust=\"untrusted\">\n" + context + "\n</hook-context>"));
        return new io.github.liumaishenjian.ccjava.domain.ModelRequest(
                request.sessionId(), request.runId(), request.turnNumber(), messages, request.toolDefinitions());
    }

    /**
     * 在 Run 唯一终态清除尚未消费的短生命周期 Context。
     *
     * @param runId 已到达终态的 Run
     */
    public void clearTransientContext(io.github.liumaishenjian.ccjava.domain.RunId runId) {
        pendingContext.remove(Objects.requireNonNull(runId, "runId 不能为空"));
    }

    private HookExecutionResult invoke(
            HookBinding binding,
            HookInvocation invocation,
            CancellationToken cancellationToken) {
        if (!binding.trusted()) {
            return failureResult(binding, HookExecutionStatus.SKIPPED_UNTRUSTED, "Hook 未通过信任检查");
        }
        if (cancellationToken.isCancellationRequested()) {
            return failureResult(binding, HookExecutionStatus.CANCELLED, "Run 已取消");
        }
        try {
            HookExecutionResult result = binding.handler().execute(invocation, cancellationToken);
            if (result == null) {
                return failureResult(binding, HookExecutionStatus.INVALID_OUTPUT, "Hook 返回空结果");
            }
            if (result.status() != HookExecutionStatus.COMPLETED) {
                return failureResult(
                        binding,
                        result.status(),
                        result.reason().orElse("Hook 未完成"));
            }
            HookDisposition disposition = binding.failurePolicy() == HookFailurePolicy.OBSERVE_ONLY
                    ? HookDisposition.CONTINUE
                    : result.disposition();
            return new HookExecutionResult(
                    binding.id(),
                    disposition,
                    result.status(),
                    result.reason(),
                    result.additionalContext());
        } catch (RuntimeException exception) {
            return failureResult(binding, HookExecutionStatus.FAILED, "Hook 执行失败");
        }
    }

    private HookExecutionResult failureResult(
            HookBinding binding,
            HookExecutionStatus status,
            String reason) {
        HookDisposition disposition = failureDisposition(binding, status);
        return new HookExecutionResult(
                binding.id(),
                disposition,
                status,
                Optional.of(reason),
                Optional.empty());
    }

    private HookDisposition failureDisposition(HookBinding binding, HookExecutionStatus status) {
        if (binding.failurePolicy() != HookFailurePolicy.FAIL_CLOSED) {
            return HookDisposition.CONTINUE;
        }
        return binding.matcher().event() == HookEventKind.PERMISSION_REQUEST
                ? HookDisposition.DENY
                : HookDisposition.BLOCK;
    }

    private static HookAggregateResult aggregate(
            HookEventKind event,
            List<HookExecutionResult> results) {
        HookDisposition disposition = HookDisposition.CONTINUE;
        Optional<String> blockingReason = Optional.empty();
        StringBuilder context = new StringBuilder();
        for (HookExecutionResult result : results) {
            HookDisposition candidate = event.blockingAllowed()
                    ? result.disposition()
                    : HookDisposition.CONTINUE;
            if (candidate == HookDisposition.DENY) {
                disposition = HookDisposition.DENY;
            } else if (candidate == HookDisposition.BLOCK && disposition != HookDisposition.DENY) {
                disposition = HookDisposition.BLOCK;
            } else if (candidate == HookDisposition.ALLOW
                    && disposition == HookDisposition.CONTINUE) {
                disposition = HookDisposition.ALLOW;
            }
            if (blockingReason.isEmpty()
                    && (candidate == HookDisposition.BLOCK || candidate == HookDisposition.DENY)) {
                blockingReason = result.reason();
            }
            result.additionalContext().ifPresent(text -> {
                if (context.length() == 0) {
                    context.append(text);
                } else if (context.length() + 1 + text.length() <= HookAggregateResult.MAX_CONTEXT_CHARACTERS) {
                    context.append('\n').append(text);
                }
            });
        }
        return new HookAggregateResult(
                event,
                disposition,
                results,
                context.length() == 0 ? Optional.empty() : Optional.of(context.toString()),
                blockingReason);
    }

    private static List<HookBinding> sortBindings(List<HookBinding> source) {
        Objects.requireNonNull(source, "bindings 不能为空");
        return source.stream()
                .map(Objects::requireNonNull)
                .sorted(Comparator.comparingInt(HookBinding::order).thenComparing(HookBinding::id))
                .toList();
    }
}
