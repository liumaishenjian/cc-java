package io.github.liumaishenjian.ccjava.domain;

/**
 * 描述 Tool 在持续规划阶段可声明的最窄能力，而不是推断工具名称。
 *
 * <p>能力由可信注册边缘写入 Definition；模型参数、Tool 名称和 Prompt 不能扩大能力。
 * 未显式声明的外部、MCP 与 Plugin Tool 在规划阶段一律不可用。</p>
 *
 * @since 0.1.0
 */
public enum PlanToolCapability {
    /** 仅在受控 Workspace 内读取。 */
    READ_ONLY_LOCAL,
    /** 受 Permission/AutoReview 约束的只读网络访问。 */
    READ_ONLY_NETWORK,
    /** 只修改当前 Session 独占的 PlanArtifact。 */
    PLAN_ARTIFACT_WRITE,
    /** 暂停模型循环并向用户提出结构化问题。 */
    USER_QUESTION,
    /** 可选、受限且只读的子 Agent；本批生产装配尚未启用。 */
    BOUNDED_READ_ONLY_SUBAGENT
}
