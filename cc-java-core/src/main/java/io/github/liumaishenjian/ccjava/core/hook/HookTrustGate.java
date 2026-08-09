package io.github.liumaishenjian.ccjava.core.hook;

import io.github.liumaishenjian.ccjava.domain.hook.HookSourceKind;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 将 Hook 来源和配置指纹转换成 Core 可消费的 trusted 绑定。
 *
 * <p>项目共享和项目本机来源默认不可信：必须同时满足 Workspace 已被信任、指纹格式合法、
 * 且 Trust Store 中存在精确批准记录。默认、用户、Session 和 CLI 来源表示当前安装者或
 * 当前调用者显式控制的配置，可以进入 trusted 状态，但仍不会绕过 Coordinator 的超时、
 * 取消、失败策略或 Tool Permission。任何失败都返回 {@code trusted=false}，由现有
 * {@link HookCoordinator} 按绑定策略安全收敛。</p>
 *
 * @since 0.9.0
 */
public final class HookTrustGate {

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    /** 创建无状态的 Hook 信任评估器。 */
    public HookTrustGate() {
    }

    /** 供诊断和测试使用的信任结果。 */
    public enum TrustStatus {
        /** 绑定通过当前来源的信任规则。 */
        TRUSTED,
        /** 指纹不是固定的小写 SHA-256 形式。 */
        INVALID_FINGERPRINT,
        /** 当前 Workspace 尚未通过信任 Gate。 */
        WORKSPACE_UNTRUSTED,
        /** 项目来源缺少显式批准。 */
        APPROVAL_REQUIRED,
        /** 配置内容已经变化，旧批准不能复用。 */
        FINGERPRINT_MISMATCH
    }

    /**
     * Trust Gate 的安全投影；不携带命令正文或原始配置。
     *
     * @param binding 应用 trusted 结果后的绑定
     * @param status 固定信任终态
     * @param fingerprint 当前规范化配置指纹
     */
    public record Evaluation(HookBinding binding, TrustStatus status, Optional<String> fingerprint) {
        /** 校验状态与指纹存在性保持一致。 */
        public Evaluation {
            binding = Objects.requireNonNull(binding, "binding 不能为空");
            status = Objects.requireNonNull(status, "status 不能为空");
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
            if ((status == TrustStatus.INVALID_FINGERPRINT) != fingerprint.isEmpty()) {
                throw new IllegalArgumentException("Trust 状态与指纹存在性不一致");
            }
            if (status != TrustStatus.INVALID_FINGERPRINT && fingerprint.isEmpty()) {
                throw new IllegalArgumentException("有效 Trust 状态必须携带指纹");
            }
        }
    }

    /**
     * 评估一条 Hook 绑定，不启动 Handler。
     *
     * @param binding 已解析但尚未通过本次 Trust Gate 的绑定
     * @param source 配置来源
     * @param fingerprint 配置规范化后的指纹
     * @param workspaceTrusted 项目来源所在 Workspace 是否已获得显式信任
     * @param trustStore 用户批准记录端口
     * @return 带有安全 trusted 标记的评估结果
     */
    public Evaluation evaluate(
            HookBinding binding,
            HookSourceKind source,
            String fingerprint,
            boolean workspaceTrusted,
            HookTrustStore trustStore) {
        HookBinding checkedBinding = Objects.requireNonNull(binding, "binding 不能为空");
        HookSourceKind checkedSource = Objects.requireNonNull(source, "source 不能为空");
        HookTrustStore checkedStore = Objects.requireNonNull(trustStore, "trustStore 不能为空");
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            return result(checkedBinding, TrustStatus.INVALID_FINGERPRINT, false, Optional.empty());
        }
        TrustStatus status;
        boolean trusted;
        if (isProjectSource(checkedSource) && !workspaceTrusted) {
            status = TrustStatus.WORKSPACE_UNTRUSTED;
            trusted = false;
        } else if (isProjectSource(checkedSource)) {
            Optional<String> approved = checkedStore.approvedFingerprint(checkedBinding.id(), checkedSource)
                    .filter(HookTrustGate::isFingerprint);
            if (approved.isEmpty()) {
                status = TrustStatus.APPROVAL_REQUIRED;
                trusted = false;
            } else if (!approved.orElseThrow().equals(fingerprint)) {
                status = TrustStatus.FINGERPRINT_MISMATCH;
                trusted = false;
            } else {
                status = TrustStatus.TRUSTED;
                trusted = true;
            }
        } else {
            status = TrustStatus.TRUSTED;
            trusted = true;
        }
        return result(checkedBinding, status, trusted, Optional.of(fingerprint));
    }

    private static Evaluation result(
            HookBinding binding,
            TrustStatus status,
            boolean trusted,
            Optional<String> fingerprint) {
        HookBinding evaluated = new HookBinding(
                binding.id(), binding.matcher(), binding.handler(), binding.failurePolicy(), trusted, binding.order());
        return new Evaluation(evaluated, status, fingerprint);
    }

    private static boolean isProjectSource(HookSourceKind source) {
        return source == HookSourceKind.PROJECT_SHARED || source == HookSourceKind.PROJECT_LOCAL;
    }

    private static boolean isFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }
}
