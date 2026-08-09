package io.github.liumaishenjian.ccjava.domain.hook;

/**
 * S09 对外 Hook 可观察的生命周期阶段。
 *
 * <p>内部 {@code LifecycleEvent} 仍是 Runtime 的规范事实；这个枚举只是把
 * 允许外部扩展订阅的事件收敛成稳定、版本化的项目自有协议。只有带有明确
 * 决策点的事件才允许 Hook 产生阻断结果。</p>
 *
 * @since 0.1.0
 */
public enum HookEventKind {

    /** Session 建立后。 */
    SESSION_START(false),
    /** Session 显式结束前。 */
    SESSION_END(false),
    /** 用户消息接受后、Run 建立前。 */
    USER_PROMPT(true),
    /** Run 建立后。 */
    RUN_START(false),
    /** Run 进入唯一终态后。 */
    RUN_END(false),
    /** 一个模型回合开始前。 */
    MODEL_TURN_START(false),
    /** 一个模型回合聚合完成后。 */
    MODEL_TURN_END(false),
    /** Tool 参数校验通过后、Permission 前。 */
    PRE_TOOL(true),
    /** Tool Result 规范化并记录后。 */
    POST_TOOL(false),
    /** Permission Policy 得到 ASK 后、用户审批前。 */
    PERMISSION_REQUEST(true),
    /** Context 压缩开始前。 */
    PRE_COMPACT(true),
    /** Context 压缩完成后。 */
    POST_COMPACT(false),
    /** 子 Scope 物化前；可信 Hook 可阻断或附加有界非可信 Context。 */
    SUB_AGENT_START(true),
    /** 子任务唯一终态持久后；只观察且不能改写终态。 */
    SUB_AGENT_STOP(false),
    /** HOOK-11 L1：宿主预注册的 Agent definition 纯收窄决策点。 */
    AGENT_DEFINITION(true);

    private final boolean blockingAllowed;

    HookEventKind(boolean blockingAllowed) {
        this.blockingAllowed = blockingAllowed;
    }

    /**
     * 判断该事件的 Hook 结果是否可以影响 Runtime 控制流。
     *
     * @return 允许阻断或否决时为 {@code true}
     */
    public boolean blockingAllowed() {
        return blockingAllowed;
    }
}
