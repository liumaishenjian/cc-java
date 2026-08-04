package io.github.liumaishenjian.ccjava.domain;

/**
 * Memory mutation 的隐私安全诊断分类。
 *
 * <p>分类只表达失败阶段，不携带候选正文、Secret、绝对路径或底层异常文案。</p>
 *
 * @since 0.7.0
 */
public enum MemoryMutationDiagnosticKind {
    /** 创建目标已经存在。 */
    TOPIC_ALREADY_EXISTS,

    /** 更新或删除目标不存在。 */
    TOPIC_NOT_FOUND,

    /** expected digest 缺失、格式错误或与当前安全读取不一致。 */
    DIGEST_CONFLICT,

    /** M1 已达到 topic 数量上限。 */
    TOPIC_LIMIT_REACHED,

    /** 完整序列化 topic 超过字节、行数或字段上限。 */
    CONTENT_LIMIT_EXCEEDED,

    /** 候选包含明显 Secret 或 Provider endpoint 赋值。 */
    SECRET_CANDIDATE_REJECTED,

    /** 目标、暂存文件或 root 是链接、重解析点、越界路径或不支持的类型。 */
    UNSAFE_PATH,

    /** 目标在提交或删除前发生身份、大小或内容变化。 */
    FILE_CHANGED_DURING_COMMIT,

    /** 当前文件系统不支持所要求的原子 Move 语义。 */
    ATOMIC_MOVE_UNAVAILABLE,

    /** 安全读取、暂存、提交、删除或清理无法完成。 */
    IO_FAILURE,

    /** M1 已成功，但随后 M2 持久重建失败；M1 不回滚。 */
    INDEX_REBUILD_FAILED
}
