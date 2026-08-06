package io.github.liumaishenjian.ccjava.domain.command;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.List;
import java.util.Objects;

/**
 * Surface 已解码的 Session Command 意图。
 *
 * <p>所有携带用户文本的变体只可由 Application 层消费，不能被终态事件回显。</p>
 *
 * @since 0.8.0
 */
public sealed interface SessionCommandIntent permits SessionCommandIntent.Help, SessionCommandIntent.Clear,
        SessionCommandIntent.Compact, SessionCommandIntent.Context, SessionCommandIntent.Doctor,
        SessionCommandIntent.ModelChange, SessionCommandIntent.Permissions, SessionCommandIntent.Resume {
    /**
     * 返回封闭命令类别。
     *
     * @return 与意图变体一致的类别
     */
    SessionCommandKind kind();

    /** 显示当前 Surface 支持及延期能力。 */
    record Help() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.HELP; }
    }

    /** 清理当前 Surface 的短生命周期交互状态。 */
    record Clear() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.CLEAR; }
    }

    /**
     * 请求既有 S07 Gate 支持的显式压缩。
     *
     * @param anchors 仅供后续安全 adapter 消费的锚点，不会被终态事件回显
     */
    record Compact(List<String> anchors) implements SessionCommandIntent {
        /**
         * 冻结有界锚点列表。
         *
         * @param anchors 未解析的压缩锚点
         */
        public Compact {
            anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors 不能为空"));
            if (anchors.size() > 32 || anchors.stream().anyMatch(SessionCommandIntent::invalidText)) {
                throw new IllegalArgumentException("compact anchors 非法");
            }
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.COMPACT; }
        @Override public String toString() { return "Compact[anchors=<redacted>]"; }
    }

    /** 请求最新 Context Usage 的安全投影。 */
    record Context() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.CONTEXT; }
    }

    /** 请求已发布状态的只读诊断。 */
    record Doctor() implements SessionCommandIntent {
        @Override public SessionCommandKind kind() { return SessionCommandKind.DOCTOR; }
    }

    /**
     * 请求下一 Run 的模型变更。
     *
     * @param modelName 仅供后续安全 provider adapter 消费的模型名，不会被终态事件回显
     */
    record ModelChange(String modelName) implements SessionCommandIntent {
        /**
         * 验证有界且无控制字符的模型名。
         *
         * @param modelName 未解析模型名
         */
        public ModelChange {
            if (invalidText(modelName)) throw new IllegalArgumentException("modelName 非法");
        }
        @Override public SessionCommandKind kind() { return SessionCommandKind.MODEL_CHANGE; }
        @Override public String toString() { return "ModelChange[modelName=<redacted>]"; }
    }

    /**
     * 请求权限安全视图或仅变更 PermissionMode；本切片不暴露或编辑 selector/规则。
     *
     * @param operation 封闭查询或模式变更
     */
    record Permissions(PermissionsOperation operation) implements SessionCommandIntent {
        /**
         * 创建不含规则文本的封闭权限请求。
         *
         * @param operation 封闭权限动作
         */
        public Permissions { operation = Objects.requireNonNull(operation, "operation 不能为空"); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.PERMISSIONS; }
    }

    /**
     * 请求恢复指定 Session。
     *
     * @param sessionId 仅供后续 S06 recovery-gated adapter 消费的会话标识
     */
    record Resume(SessionId sessionId) implements SessionCommandIntent {
        /**
         * 验证恢复目标标识。
         *
         * @param sessionId 目标会话标识
         */
        public Resume { sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空"); }
        @Override public SessionCommandKind kind() { return SessionCommandKind.RESUME; }
        @Override public String toString() { return "Resume[sessionId=<redacted>]"; }
    }

    /** 不解析 selector、规则或 grant 的封闭权限动作。 */
    sealed interface PermissionsOperation permits PermissionsOperation.Query, PermissionsOperation.ModeChange {
        /** 仅查询当前已发布安全状态。 */
        record Query() implements PermissionsOperation { }

        /**
         * 仅替换下一 Run 的 PermissionMode 默认值。
         *
         * @param mode 已封闭的 S05 PermissionMode
         */
        record ModeChange(PermissionMode mode) implements PermissionsOperation {
            /**
             * 验证已封闭的 S05 PermissionMode。
             *
             * @param mode 已封闭的 S05 PermissionMode
             */
            public ModeChange { mode = Objects.requireNonNull(mode, "mode 不能为空"); }
        }
    }

    private static boolean invalidText(String value) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > 256
                || value.chars().anyMatch(Character::isISOControl);
    }
}
