package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewRequest;
import io.github.liumaishenjian.ccjava.domain.ApprovalReviewResult;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.PermissionOutcome;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * 把 Hook 后仍为 final ASK 的调用收敛为一次允许或拒绝。
 *
 * <p>该协调器只处理调用方已确定为 ASK 的输入，不重新执行 Permission Policy 或 Hook。只有共享
 * token 在 Gateway 前或后确实已取消时，取消才是 Run 控制流并抛出 {@link CancellationException}；
 * Gateway 自行抛出取消异常或报告 CANCELLED 但 token 未取消时，均按 INTERNAL 失败关闭并计入
 * circuit，避免边缘 Adapter 伪造 Run 取消。</p>
 *
 * @since 0.15.0
 */
public final class AutoReviewCoordinator {
    private final ApprovalReviewGateway gateway;

    /**
     * 创建只依赖一个受限审查 Gateway 的协调器。
     *
     * @param gateway 自动审查模型端口
     */
    public AutoReviewCoordinator(ApprovalReviewGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
    }

    /**
     * 审查最终 ASK，并把一次允许、拒绝或失败关闭收敛为类型化决定。
     *
     * @param finalOutcome Hook 后的最终权限结果
     * @param request 脱敏且有界的审查请求
     * @param token 当前 Run 的共享取消信号
     * @param circuit 当前 Run 独占的连续 non-allow circuit
     * @return 当前调用的类型化结果；第三次 non-allow 仍返回当前拒绝并带停止信号
     * @throws CancellationException 共享 Run token 已确认取消时
     */
    public AutoReviewDecision reviewFinalAsk(PermissionOutcome finalOutcome, ApprovalReviewRequest request,
            CancellationToken token, AutoReviewCircuit circuit) {
        Objects.requireNonNull(finalOutcome, "finalOutcome 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(token, "token 不能为空");
        Objects.requireNonNull(circuit, "circuit 不能为空");
        if (finalOutcome.decision() != PermissionDecision.ASK) {
            return AutoReviewDecision.notFinalAsk();
        }
        throwIfCancelled(token);
        AutoReviewCircuit.AcquireStatus acquired = circuit.acquire(request.runId());
        if (acquired == AutoReviewCircuit.AcquireStatus.CIRCUIT_OPEN) {
            return AutoReviewDecision.stopped(AutoReviewDecision.Status.CIRCUIT_OPEN);
        }
        if (acquired == AutoReviewCircuit.AcquireStatus.RUN_CLOSED) {
            return AutoReviewDecision.stopped(AutoReviewDecision.Status.RUN_CLOSED);
        }
        ApprovalReviewResult result;
        try {
            result = Objects.requireNonNull(gateway.review(request, token), "gateway 返回 null");
        } catch (CancellationException cancelled) {
            if (token.isCancellationRequested()) {
                throw cancelled;
            }
            return fail(request, circuit, ApprovalReviewResult.FailureKind.INTERNAL);
        } catch (RuntimeException failure) {
            return fail(request, circuit, ApprovalReviewResult.FailureKind.INTERNAL);
        }
        throwIfCancelled(token);
        if (result.failure().isPresent()) {
            ApprovalReviewResult.FailureKind kind = result.failure().orElseThrow();
            if (kind == ApprovalReviewResult.FailureKind.CANCELLED) {
                if (token.isCancellationRequested()) {
                    throw new CancellationException("Auto Review cancelled");
                }
                return fail(request, circuit, ApprovalReviewResult.FailureKind.INTERNAL);
            }
            return fail(request, circuit, kind);
        }
        if (result.verdict().orElseThrow() == ApprovalReviewResult.Verdict.ALLOW_ONCE) {
            circuit.recordAllow(request.runId());
            return AutoReviewDecision.allowOnce();
        }
        return AutoReviewDecision.deny(circuit.recordNonAllow(request.runId()));
    }

    private static AutoReviewDecision fail(ApprovalReviewRequest request, AutoReviewCircuit circuit,
            ApprovalReviewResult.FailureKind kind) {
        return AutoReviewDecision.failed(kind, circuit.recordNonAllow(request.runId()));
    }

    private static void throwIfCancelled(CancellationToken token) {
        if (token.isCancellationRequested()) {
            throw new CancellationException("Auto Review cancelled");
        }
    }
}
