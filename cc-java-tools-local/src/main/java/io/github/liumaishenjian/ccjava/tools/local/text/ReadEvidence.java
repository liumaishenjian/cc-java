package io.github.liumaishenjian.ccjava.tools.local.text;

import java.util.Objects;

/**
 * 一次已完成 Read 留下的可验证证据：读了哪些行、当时文件是什么样。
 *
 * <p>该记录只包含身份与内容摘要，不包含文件正文、绝对路径或凭证，因此可以安全地
 * 长期驻留在有界登记表中。{@link #contentDigest()} 是被覆盖行区间规范化文本的
 * 稳定摘要，用来发现“大小与修改时间都没变但内容被改写”的并发修改。</p>
 *
 * @param protocolPath 相对 Workspace 的稳定协议路径
 * @param firstLine 覆盖区间的 1-based 起始行
 * @param lastLine 覆盖区间的 1-based 结束行（含）；空文件为 0
 * @param completeFile 是否覆盖了从第一行到文件末尾的全部内容
 * @param sizeBytes 读取瞬间的文件字节数
 * @param lastModifiedMillis 读取瞬间的最后修改时间
 * @param contentDigest 覆盖区间规范化文本的摘要
 * @since 0.8.0
 */
public record ReadEvidence(
        String protocolPath,
        int firstLine,
        int lastLine,
        boolean completeFile,
        long sizeBytes,
        long lastModifiedMillis,
        long contentDigest) {

    /** 校验 Read 证据。 */
    public ReadEvidence {
        protocolPath = Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        if (firstLine < 1) {
            throw new IllegalArgumentException("firstLine 必须从 1 开始");
        }
        if (lastLine < firstLine - 1) {
            throw new IllegalArgumentException("lastLine 不能早于 firstLine 的前一行");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
    }

    /**
     * 判断本证据是否覆盖给定的 1-based 闭区间行。
     *
     * @param regionFirstLine 区间起始行
     * @param regionLastLine 区间结束行
     * @return 完整覆盖时为 {@code true}
     */
    public boolean covers(int regionFirstLine, int regionLastLine) {
        if (completeFile) {
            return true;
        }
        return regionFirstLine >= firstLine && regionLastLine <= lastLine;
    }

    /**
     * 为规范化文本片段计算稳定摘要。
     *
     * <p>使用与平台无关的 64 位滚动摘要；它只用于发现内容变化，不用于安全签名。</p>
     *
     * @param canonicalText 规范化为 {@code \n} 的文本片段
     * @return 稳定摘要
     */
    public static long digestOf(String canonicalText) {
        Objects.requireNonNull(canonicalText, "canonicalText 不能为空");
        long digest = 0xcbf29ce484222325L;
        for (int index = 0; index < canonicalText.length(); index++) {
            digest ^= canonicalText.charAt(index);
            digest *= 0x100000001b3L;
        }
        return digest ^ canonicalText.length();
    }
}
