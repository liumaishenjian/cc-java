package io.github.liumaishenjian.ccjava.domain;

/**
 * Tool Result 被有界裁剪的稳定原因。
 *
 * <p>原因用于模型纠正和安全 Surface 统计，不包含原始内容或路径。后续 Stage 可以新增原因，
 * 但不得改变已有枚举值的语义。</p>
 *
 * @since 0.3.0
 */
public enum ToolResultTruncationReason {

    /** 结果完整，未发生裁剪。 */
    NONE,

    /** 按行读取达到调用上限。 */
    LINE_LIMIT,

    /** 枚举或搜索达到条目上限。 */
    ITEM_LIMIT,

    /** 遍历达到最大深度。 */
    DEPTH_LIMIT,

    /** 搜索达到文件数量预算。 */
    FILE_LIMIT,

    /** 搜索达到累计扫描字节预算。 */
    SCAN_BYTE_LIMIT,

    /** Git 或其他有界 Adapter 达到字节预算。 */
    BYTE_LIMIT,

    /** Pipeline 根据 Tool Definition 或全局 ceiling 执行最终裁剪。 */
    PIPELINE_CHARACTER_LIMIT
}
