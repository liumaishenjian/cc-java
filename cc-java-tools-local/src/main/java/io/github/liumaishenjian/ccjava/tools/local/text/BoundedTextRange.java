package io.github.liumaishenjian.ccjava.tools.local.text;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 一次有界行范围读取的结果，是模型可继续读取的权威证据。
 *
 * <p>该记录严格区分“已知”与“未知”：只有当本次扫描确实读到文件末尾时，
 * {@link #totalLines()} 与 {@link #totalBytes()} 才有值；否则必须保持为空，
 * 不允许用当前页的统计冒充整份文件的统计。{@link #hasMore()} 为 {@code true} 时
 * {@link #nextStartLine()} 必须是下一次调用应当使用的 1-based 行号。</p>
 *
 * @param firstLine 本页第一行的 1-based 行号
 * @param lines 本页各行的规范化文本，不含行分隔符
 * @param hasMore 起始行之后是否仍有未返回的内容
 * @param nextStartLine 下一页起始行；{@code hasMore} 为 {@code false} 时无意义
 * @param totalLines 仅当扫描到文件末尾时给出的总行数
 * @param totalBytes 仅当扫描到文件末尾时给出的总字节数
 * @param truncatedLines 因单行字符预算被截断的行数
 * @param scanCeilingReached 是否因扫描字节 ceiling 停止，而不是因为达到行预算
 * @since 0.8.0
 */
public record BoundedTextRange(
        int firstLine,
        List<String> lines,
        boolean hasMore,
        int nextStartLine,
        OptionalLong totalLines,
        OptionalLong totalBytes,
        int truncatedLines,
        boolean scanCeilingReached) {

    /** 冻结并校验一页读取结果。 */
    public BoundedTextRange {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines 不能为空"));
        totalLines = Objects.requireNonNull(totalLines, "totalLines 不能为空");
        totalBytes = Objects.requireNonNull(totalBytes, "totalBytes 不能为空");
        if (firstLine < 1) {
            throw new IllegalArgumentException("firstLine 必须从 1 开始");
        }
        if (truncatedLines < 0 || truncatedLines > lines.size()) {
            throw new IllegalArgumentException("truncatedLines 必须落在本页行数内");
        }
        if (hasMore && nextStartLine <= 0) {
            throw new IllegalArgumentException("hasMore 时必须给出 nextStartLine");
        }
        if (hasMore && totalLines.isPresent()) {
            throw new IllegalArgumentException("仍有后续内容时不能声称已知总行数");
        }
    }

    /**
     * 指示本页是否覆盖了从第一行到文件末尾的全部内容。
     *
     * @return 完整读取时为 {@code true}
     */
    public boolean completeFile() {
        return firstLine == 1 && !hasMore && totalLines.isPresent() && truncatedLines == 0;
    }

    /**
     * 返回本页最后一行的 1-based 行号。
     *
     * @return 最后一行行号；空页时为 {@code firstLine - 1}
     */
    public int lastLine() {
        return firstLine + lines.size() - 1;
    }
}
