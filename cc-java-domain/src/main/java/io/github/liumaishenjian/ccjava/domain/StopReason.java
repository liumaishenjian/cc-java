package io.github.liumaishenjian.ccjava.domain;

/**
 * Agent Run 的稳定终止原因。
 *
 * <p>该枚举保留后续 Stage 所需的协议值。S02 在 S01 已有终态之外，
 * 开始主动产生 {@link #USER_CANCELLED}、{@link #TIME_LIMIT_REACHED} 和
 * {@link #MODEL_OUTPUT_LIMIT_REACHED}；尚未进入对应 Stage 的值不能被宣传为
 * 已实现能力。</p>
 *
 * @since 0.1.0
 */
public enum StopReason {

    /** 模型给出不含 Tool Call 的最终回复。 */
    COMPLETED,

    /** 用户取消当前 Run。 */
    USER_CANCELLED,

    /** 模型 Provider 调用失败。 */
    MODEL_ERROR,

    /** 模型既未返回文本，也未返回有效 Tool Call。 */
    INVALID_MODEL_RESPONSE,

    /** Provider 报告长度终止，或 Adapter 达到本地响应安全上限。 */
    MODEL_OUTPUT_LIMIT_REACHED,

    /** 已达到模型回合上限。 */
    TURN_LIMIT_REACHED,

    /** 下一批 Tool Call 超出工具数量上限。 */
    TOOL_LIMIT_REACHED,

    /** 已达到 Run 时间限制。 */
    TIME_LIMIT_REACHED,

    /** Context 无法在安全预算内继续组装。 */
    CONTEXT_LIMIT_REACHED,

    /** 关键操作被拒绝且 Run 无法继续。 */
    PERMISSION_DENIED,

    /** 不可恢复的 Tool 错误。 */
    TOOL_ERROR,

    /** Runtime 不变量被破坏或出现未分类错误。 */
    INTERNAL_ERROR
}
