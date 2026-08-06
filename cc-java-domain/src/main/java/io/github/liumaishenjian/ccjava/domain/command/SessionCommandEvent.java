package io.github.liumaishenjian.ccjava.domain.command;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.List;
import java.util.Objects;

/**
 * 一次命令请求的唯一终态安全事件。
 *
 * @param kind 已分派命令类别
 * @param commandId 请求关联标识
 * @param sessionId 处理时的当前会话标识
 * @param status 终态分类
 * @param code 固定终态代码
 * @param payload 白名单投影
 * @since 0.8.0
 */
public record SessionCommandEvent(SessionCommandKind kind, CommandId commandId, SessionId sessionId,
                                  SessionCommandStatus status, SessionCommandResultCode code,
                                  SessionCommandPayload payload) {
    /** 冻结终态事件的安全组件。 */
    public SessionCommandEvent {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        commandId = Objects.requireNonNull(commandId, "commandId 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        status = Objects.requireNonNull(status, "status 不能为空");
        code = Objects.requireNonNull(code, "code 不能为空");
        payload = Objects.requireNonNull(payload, "payload 不能为空");
        requireStatusCode(status, code);
    }

    /** 命令 Event 的封闭白名单 payload。 */
    public sealed interface SessionCommandPayload permits EmptyPayload, HelpPayload, ContextPayload, DoctorPayload { }

    /** 无额外数据的安全确认。 */
    public record EmptyPayload() implements SessionCommandPayload { }

    /**
     * 当前命令的可用与延期状态。
     *
     * @param commands 每个封闭命令类别的静态支持状态
     */
    public record HelpPayload(List<CommandAvailability> commands) implements SessionCommandPayload {
        /**
         * 冻结命令可达性列表。
         *
         * @param commands 命令支持状态
         */
        public HelpPayload { commands = List.copyOf(Objects.requireNonNull(commands, "commands 不能为空")); }
    }

    /**
     * 一个命令的静态支持状态。
     *
     * @param kind 命令类别
     * @param support 当前切片中的支持等级
     */
    public record CommandAvailability(SessionCommandKind kind, CommandSupport support) {
        /**
         * 验证命令支持状态。
         *
         * @param kind 命令类别
         * @param support 支持等级
         */
        public CommandAvailability {
            kind = Objects.requireNonNull(kind, "kind 不能为空");
            support = Objects.requireNonNull(support, "support 不能为空");
        }
    }

    /** 命令可达性分类。 */
    public enum CommandSupport {
        /** 命令可在当前 Java Application 切片中执行。 */
        AVAILABLE,
        /** 命令需要后续安全 adapter 或 Surface。 */
        DEFERRED,
        /** 当前不存在可安全调用的实现。 */
        NOT_AVAILABLE
    }

    /**
     * Context Usage 的数值和枚举投影。
     *
     * @param systemTokens system token 估计值
     * @param transcriptTokens transcript token 估计值
     * @param toolTokens tool token 估计值
     * @param memoryTokens memory token 估计值
     * @param totalTokens 总 token 估计值
     * @param availableInputTokens 输入预算
     * @param freeTokens 剩余预算
     * @param overflowTokens 超出预算的 token 数
     * @param sourceRevision 已发布 source revision
     * @param estimateKind 估计方式枚举名
     * @param status preparation 状态枚举名
     * @param reductionStrategies 已应用压缩策略枚举名
     * @param reasonCodes 固定原因代码枚举名
     * @param modelRequestAttempts 模型请求次数
     */
    public record ContextPayload(long systemTokens, long transcriptTokens, long toolTokens, long memoryTokens,
                                 long totalTokens, long availableInputTokens, long freeTokens, long overflowTokens,
                                 long sourceRevision, String estimateKind, String status,
                                 List<String> reductionStrategies, List<String> reasonCodes, int modelRequestAttempts)
            implements SessionCommandPayload {
        /** 验证白名单 Context 投影的数值和枚举。 */
        public ContextPayload {
            if (systemTokens < 0 || transcriptTokens < 0 || toolTokens < 0 || memoryTokens < 0 || totalTokens < 0
                    || availableInputTokens <= 0 || overflowTokens < 0 || sourceRevision < 0 || modelRequestAttempts < 0) {
                throw new IllegalArgumentException("context payload 数值非法");
            }
            estimateKind = boundedEnum(estimateKind, "estimateKind");
            status = boundedEnum(status, "status");
            reductionStrategies = boundedEnums(reductionStrategies, "reductionStrategies");
            reasonCodes = boundedEnums(reasonCodes, "reasonCodes");
        }
    }

    /**
     * Doctor 的来源与状态白名单投影。
     *
     * @param settingsAvailable 是否已有设置 LKG
     * @param settingsRevision 已发布设置 revision
     * @param instructionCount 已发布指令来源数
     * @param contextAvailable 是否已有 Context Usage
     * @param activeRun 是否存在活动 Run
     * @param entries 固定来源和状态条目
     */
    public record DoctorPayload(boolean settingsAvailable, long settingsRevision, int instructionCount,
                                boolean contextAvailable, boolean activeRun, List<DoctorEntry> entries)
            implements SessionCommandPayload {
        /** 验证并冻结 doctor 安全投影。 */
        public DoctorPayload {
            if (settingsRevision < 0 || instructionCount < 0) throw new IllegalArgumentException("doctor 数值非法");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries 不能为空"));
            if (entries.size() > 128) throw new IllegalArgumentException("doctor entries 过多");
        }
    }

    /**
     * 不含正文或物理路径的 doctor 条目。
     *
     * @param component 固定组件类别
     * @param sourceKind 固定来源类别
     * @param safeId 非绝对路径的安全来源标识
     * @param code 固定状态代码
     * @param severity 固定严重程度
     */
    public record DoctorEntry(String component, String sourceKind, String safeId, String code, String severity) {
        /** 验证 doctor 条目的白名单组件。 */
        public DoctorEntry {
            component = boundedEnum(component, "component");
            sourceKind = boundedEnum(sourceKind, "sourceKind");
            safeId = boundedSafeId(safeId);
            code = boundedEnum(code, "code");
            severity = boundedEnum(severity, "severity");
        }
    }

    private static void requireStatusCode(SessionCommandStatus status, SessionCommandResultCode code) {
        boolean valid = switch (status) {
            case SUCCEEDED -> code == SessionCommandResultCode.OK;
            case CANCELLED -> code == SessionCommandResultCode.CANCELLED;
            case FAILED -> code == SessionCommandResultCode.INTERNAL_FAILURE;
            case REJECTED -> code != SessionCommandResultCode.OK
                    && code != SessionCommandResultCode.CANCELLED
                    && code != SessionCommandResultCode.INTERNAL_FAILURE;
        };
        if (!valid) throw new IllegalArgumentException("终态 status 与 code 不匹配");
    }

    private static String boundedEnum(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }

    private static String boundedSafeId(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || value.indexOf('\\') >= 0
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("safeId 非法");
        return value;
    }

    private static List<String> boundedEnums(List<String> values, String name) {
        values = List.copyOf(Objects.requireNonNull(values, name + " 不能为空"));
        if (values.size() > 32) throw new IllegalArgumentException(name + " 过多");
        for (String value : values) boundedEnum(value, name);
        return values;
    }
}
