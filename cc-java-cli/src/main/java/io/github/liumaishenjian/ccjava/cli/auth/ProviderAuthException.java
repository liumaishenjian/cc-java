package io.github.liumaishenjian.ccjava.cli.auth;

import java.util.Objects;

/** Provider/Auth 控制面的 privacy-safe 结构化失败。 */
public final class ProviderAuthException extends RuntimeException {
    /** 失败类别；调用方只能展示 code 与固定修复动作。 */
    public enum Code {
        /** Provider 定义不满足结构或安全约束。 */
        PROVIDER_DEFINITION_INVALID,
        /** 指定的 Provider 不存在。 */
        PROVIDER_UNKNOWN,
        /** 指定的模型不在 Provider 模型目录中。 */
        MODEL_UNKNOWN,
        /** 模型选择同时匹配多个候选项。 */
        MODEL_SELECTION_AMBIGUOUS,
        /** 当前操作要求指定鉴权配置。 */
        AUTH_PROFILE_REQUIRED,
        /** 指定的鉴权配置不存在。 */
        AUTH_PROFILE_UNKNOWN,
        /** 鉴权配置与当前选择发生冲突。 */
        AUTH_PROFILE_CONFLICT,
        /** 当前操作要求输入鉴权密钥。 */
        AUTH_SECRET_INPUT_REQUIRED,
        /** 无法取得鉴权配置所需的密钥。 */
        AUTH_SECRET_UNAVAILABLE,
        /** 本地鉴权存储不满足安全要求。 */
        AUTH_STORE_INSECURE,
        /** 本地鉴权存储已锁定。 */
        AUTH_STORE_LOCKED,
        /** 本地鉴权存储内容损坏或无法解析。 */
        AUTH_STORE_CORRUPT,
        /** 鉴权存储事务与并发变更冲突。 */
        AUTH_TRANSACTION_CONFLICT,
        /** Provider 拒绝了探测使用的凭证。 */
        AUTH_PROBE_REJECTED,
        /** Provider 对鉴权探测实施了速率限制。 */
        AUTH_PROBE_RATE_LIMITED,
        /** Provider 不支持约定的鉴权探测。 */
        AUTH_PROBE_UNSUPPORTED,
        /** 鉴权探测无法连接 Provider。 */
        AUTH_PROBE_UNREACHABLE,
        /** 鉴权探测超过了时限。 */
        AUTH_PROBE_TIMED_OUT,
        /** 鉴权操作被调用方取消。 */
        AUTH_CANCELLED,
        /** 鉴权凭证已被撤销。 */
        AUTH_REVOKED,
        /** 登出时等待在途操作结束失败。 */
        AUTH_LOGOUT_DRAIN_FAILED,
        /** 删除本地鉴权存储失败。 */
        AUTH_STORE_DELETE_FAILED,
        /** 旧版鉴权配置缺少迁移所需信息。 */
        LEGACY_CONFIGURATION_INCOMPLETE,
        /** 旧版鉴权配置迁移与现有配置冲突。 */
        LEGACY_MIGRATION_CONFLICT
    }

    /** 用户可执行的封闭修复动作。 */
    public enum Action {
        /** 重试相同操作。 */
        RETRY,
        /** 检查并修复本地鉴权存储。 */
        CHECK_LOCAL_STORE,
        /** 重新登录并建立鉴权配置。 */
        LOGIN,
        /** 明确选择一个鉴权配置。 */
        SELECT_PROFILE,
        /** 在 Provider 侧轮换凭证。 */
        ROTATE_AT_PROVIDER,
        /** 不提供自动修复动作。 */
        NONE
    }

    /** 稳定的失败类别。 */
    private final Code code;
    /** 用户可执行的封闭修复动作。 */
    private final Action action;
    /** 调用方是否可重试相同操作。 */
    private final boolean retryable;

    /**
     * 创建不包含 cause message、路径或远端内容的失败。
     *
     * @param code 稳定的失败类别
     * @param action 用户可执行的封闭修复动作
     * @param retryable 是否允许调用方重试相同操作
     */
    public ProviderAuthException(Code code, Action action, boolean retryable) {
        super(Objects.requireNonNull(code, "code 不能为空").name());
        this.code = code; this.action = Objects.requireNonNull(action, "action 不能为空");
        this.retryable = retryable;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 此次失败的稳定类别
     */
    public Code code() { return code; }

    /**
     * 返回封闭用户动作。
     *
     * @return 用户可执行的修复动作
     */
    public Action action() { return action; }

    /**
     * 返回调用方是否可重试相同动作。
     *
     * @return 允许重试相同操作时为 {@code true}
     */
    public boolean retryable() { return retryable; }
}
