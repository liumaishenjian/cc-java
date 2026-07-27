package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;

/**
 * 表示 Session、Run、Model Turn、Permission 和 Tool Pipeline 的内部生命周期点。
 *
 * <p>事件只能用于观察和测试，S01 不提供用户 Hook DSL，也不允许观察者改变
 * Runtime 决策。</p>
 *
 * @since 0.1.0
 */
public sealed interface LifecycleEvent extends AgentEvent
        permits LifecycleEvent.SessionStarted,
                LifecycleEvent.SessionEnded,
                LifecycleEvent.RunStarted,
                LifecycleEvent.ModelTurnStarted,
                LifecycleEvent.ModelTurnCompleted,
                LifecycleEvent.BeforeTool,
                LifecycleEvent.PermissionRequested,
                LifecycleEvent.PermissionDecided,
                LifecycleEvent.AfterTool,
                LifecycleEvent.RunFinished {

    /**
     * Session 已在内存 Store 中创建。
     *
     * @param spec 创建 Session 时的稳定配置
     */
    record SessionStarted(SessionSpec spec) implements LifecycleEvent {

        /**
         * 创建 Session 启动事件。
         *
         * @param spec Session 的稳定配置
         * @throws NullPointerException 配置为空时
         */
        public SessionStarted {
            spec = Objects.requireNonNull(spec, "spec 不能为空");
        }
    }

    /**
     * Session 已显式关闭。
     */
    record SessionEnded() implements LifecycleEvent {
    }

    /**
     * Run 已接受用户消息并开始执行。
     *
     * @param request 本次 Run 的不可变请求
     */
    record RunStarted(AgentRunRequest request) implements LifecycleEvent {

        /**
         * 创建 Run 启动事件。
         *
         * @param request 本次 Run 的不可变请求
         * @throws NullPointerException 请求为空时
         */
        public RunStarted {
            request = Objects.requireNonNull(request, "request 不能为空");
        }
    }

    /**
     * Runtime 即将请求一个模型回合。
     *
     * @param turnNumber 从 1 开始的模型回合序号
     */
    record ModelTurnStarted(int turnNumber) implements LifecycleEvent {

        /**
         * 校验回合序号后创建模型回合启动事件。
         *
         * @param turnNumber 从 1 开始的模型回合序号
         * @throws IllegalArgumentException 回合序号小于 1 时
         */
        public ModelTurnStarted {
            if (turnNumber < 1) {
                throw new IllegalArgumentException("turnNumber 必须从 1 开始");
            }
        }
    }

    /**
     * 一个完整模型回合已经聚合完成。
     *
     * @param turnNumber 模型回合序号
     * @param turn       聚合后的响应
     */
    record ModelTurnCompleted(int turnNumber, ModelTurn turn) implements LifecycleEvent {

        /**
         * 校验回合信息后创建模型回合完成事件。
         *
         * @param turnNumber 从 1 开始的模型回合序号
         * @param turn 聚合后的模型响应
         * @throws NullPointerException 模型响应为空时
         * @throws IllegalArgumentException 回合序号小于 1 时
         */
        public ModelTurnCompleted {
            if (turnNumber < 1) {
                throw new IllegalArgumentException("turnNumber 必须从 1 开始");
            }
            turn = Objects.requireNonNull(turn, "turn 不能为空");
        }
    }

    /**
     * 单个 Tool Call 即将进入权限与执行管线。
     *
     * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
     * @param call    原始 Tool Call
     */
    record BeforeTool(int ordinal, ToolCall call) implements LifecycleEvent {

        /**
         * 校验调用序号后创建 Tool 执行前事件。
         *
         * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
         * @param call 原始 Tool Call
         * @throws NullPointerException Tool Call 为空时
         * @throws IllegalArgumentException 调用序号小于 1 时
         */
        public BeforeTool {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            call = Objects.requireNonNull(call, "call 不能为空");
        }
    }

    /**
     * Pipeline 正在对 Tool Call 请求权限决策。
     *
     * @param call   原始 Tool Call
     * @param effect Tool 声明的副作用
     */
    record PermissionRequested(ToolCall call, ToolEffect effect) implements LifecycleEvent {

        /**
         * 创建 Tool 权限请求事件。
         *
         * @param call 原始 Tool Call
         * @param effect Tool 声明的副作用
         * @throws NullPointerException Tool Call 或副作用为空时
         */
        public PermissionRequested {
            call = Objects.requireNonNull(call, "call 不能为空");
            effect = Objects.requireNonNull(effect, "effect 不能为空");
        }
    }

    /**
     * Tool Call 已得到最终权限决策。
     *
     * @param call     原始 Tool Call
     * @param decision 最终允许或拒绝决定
     */
    record PermissionDecided(ToolCall call, PermissionDecision decision)
            implements LifecycleEvent {

        /**
         * 校验最终决策后创建权限决定事件。
         *
         * @param call 原始 Tool Call
         * @param decision 最终权限决定
         * @throws NullPointerException Tool Call 或权限决定为空时
         * @throws IllegalArgumentException 最终决定仍为 {@code ASK} 时
         */
        public PermissionDecided {
            call = Objects.requireNonNull(call, "call 不能为空");
            decision = Objects.requireNonNull(decision, "decision 不能为空");
            if (decision == PermissionDecision.ASK) {
                throw new IllegalArgumentException("最终权限事件不能保留 ASK");
            }
        }
    }

    /**
     * 单个 Tool Call 已被规范化为 Tool Result。
     *
     * @param ordinal 本次 Run 内的 Tool Call 序号
     * @param result  规范化结果
     */
    record AfterTool(int ordinal, ToolResult result) implements LifecycleEvent {

        /**
         * 校验调用序号后创建 Tool 执行后事件。
         *
         * @param ordinal 本次 Run 内从 1 开始的 Tool Call 序号
         * @param result 规范化后的 Tool Result
         * @throws NullPointerException Tool Result 为空时
         * @throws IllegalArgumentException 调用序号小于 1 时
         */
        public AfterTool {
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal 必须从 1 开始");
            }
            result = Objects.requireNonNull(result, "result 不能为空");
        }
    }

    /**
     * Run 已进入唯一终态。
     *
     * @param result Run 终态摘要
     */
    record RunFinished(AgentRunResult result) implements LifecycleEvent {

        /**
         * 创建 Run 完成事件。
         *
         * @param result Run 的终态摘要
         * @throws NullPointerException 终态摘要为空时
         */
        public RunFinished {
            result = Objects.requireNonNull(result, "result 不能为空");
        }
    }
}
