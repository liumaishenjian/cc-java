package io.github.liumaishenjian.ccjava.core.execution;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ToolOutputSink;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionBackendId;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionFallbackDecision;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionOutcome;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionRequest;
import io.github.liumaishenjian.ccjava.domain.execution.ExecutionShell;
import io.github.liumaishenjian.ccjava.domain.execution.PlatformCapabilitySnapshot;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 根据显式 shell、有效策略和真实 probe 选择后端的 fail-closed 状态机。
 *
 * <p>Windows shell 永不进入 Linux 后端；fallback 仅当前调用一次，不创建 Session Grant。
 * 选中的后端由 identity guard 包装，在真正执行前重探测并要求完整快照与选择时一致，避免
 * helper、distribution 或 image 在 probe 与启动之间漂移。</p>
 *
 * @since 0.13.0
 */
public final class ExecutionBackendSelector {
    private final Map<ExecutionBackendId, ExecutionBackend> backends;
    private final PlatformCapabilityProbe probe;
    private final ExecutionFallbackApprover fallback;

    /** 创建固定后端集合的选择器。 */
    public ExecutionBackendSelector(
            Collection<ExecutionBackend> backends,
            PlatformCapabilityProbe probe,
            ExecutionFallbackApprover fallback) {
        var indexed = new EnumMap<ExecutionBackendId, ExecutionBackend>(
                ExecutionBackendId.class);
        for (ExecutionBackend backend : backends) {
            indexed.put(backend.id(), backend);
        }
        this.backends = Map.copyOf(indexed);
        this.probe = Objects.requireNonNull(probe, "probe 不能为空");
        this.fallback = Objects.requireNonNull(fallback, "fallback 不能为空");
    }

    /**
     * 选择并冻结当前 Call 的后端；不执行进程。
     *
     * @param request 当前 Call 请求
     * @param preference 可信启动配置指定的后端
     * @return 选中或拒绝终态
     */
    public Selection select(ExecutionRequest request, ExecutionBackendId preference) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(preference, "preference 不能为空");
        if (request.shell() == ExecutionShell.WINDOWS_PLATFORM
                && preference != ExecutionBackendId.LOCAL
                && preference != ExecutionBackendId.NATIVE_WINDOWS) {
            return Selection.rejected("SHELL_SEMANTICS_MISMATCH");
        }

        ExecutionBackend candidate = backends.get(preference);
        if (candidate == null) {
            return Selection.rejected("BACKEND_UNAVAILABLE_OR_POLICY_UNSUPPORTED");
        }
        PlatformCapabilitySnapshot snapshot = probe.probe(preference);
        boolean adequate = preference == ExecutionBackendId.LOCAL
                ? !request.policy().requireIsolation()
                : snapshot.fullyEnforced();
        if (adequate) {
            return Selection.selected(guard(candidate, snapshot), false, snapshot);
        }

        if (preference != ExecutionBackendId.LOCAL
                && !request.policy().requireIsolation()) {
            ExecutionFallbackDecision decision = fallback.decide(request, snapshot);
            if (decision.allowed() && decision.callId().equals(request.callId())) {
                ExecutionBackend local = backends.get(ExecutionBackendId.LOCAL);
                if (local != null) {
                    PlatformCapabilitySnapshot localSnapshot = probe.probe(
                            ExecutionBackendId.LOCAL);
                    return Selection.selected(
                            guard(local, localSnapshot),
                            true,
                            localSnapshot);
                }
            }
        }
        return Selection.rejected("BACKEND_UNAVAILABLE_OR_POLICY_UNSUPPORTED");
    }

    private ExecutionBackend guard(
            ExecutionBackend delegate,
            PlatformCapabilitySnapshot selectedSnapshot) {
        return new IdentityGuardedBackend(delegate, probe, selectedSnapshot);
    }

    /** 选择终态。 */
    public record Selection(
            Optional<ExecutionBackend> backend,
            boolean fallback,
            Optional<PlatformCapabilitySnapshot> capability,
            String reasonCode) {
        /** 构造选中终态。 */
        public static Selection selected(
                ExecutionBackend backend,
                boolean fallback,
                PlatformCapabilitySnapshot snapshot) {
            return new Selection(
                    Optional.of(backend),
                    fallback,
                    Optional.of(snapshot),
                    "SELECTED");
        }

        /** 构造拒绝终态。 */
        public static Selection rejected(String reasonCode) {
            return new Selection(
                    Optional.empty(),
                    false,
                    Optional.empty(),
                    reasonCode);
        }
    }

    private record IdentityGuardedBackend(
            ExecutionBackend delegate,
            PlatformCapabilityProbe probe,
            PlatformCapabilitySnapshot selectedSnapshot) implements ExecutionBackend {
        @Override
        public ExecutionBackendId id() {
            return delegate.id();
        }

        @Override
        public ExecutionOutcome execute(
                ExecutionRequest request,
                CancellationToken cancellation,
                ToolOutputSink outputSink) throws IOException {
            PlatformCapabilitySnapshot current = probe.probe(delegate.id());
            if (!selectedSnapshot.equals(current)) {
                throw new IOException("BACKEND_IDENTITY_DRIFT");
            }
            return delegate.execute(request, cancellation, outputSink);
        }
    }
}
