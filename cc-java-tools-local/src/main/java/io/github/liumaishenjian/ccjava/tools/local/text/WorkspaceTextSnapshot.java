package io.github.liumaishenjian.ccjava.tools.local.text;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 一次严格 UTF-8 整文件读取的不可变快照，同时保留模型视图与原始字节外观。
 *
 * <p>快照区分三种表示：</p>
 * <ul>
 *   <li><b>规范文本</b>（{@link #canonicalText()}）：所有 {@code \r\n} 与裸 {@code \r}
 *       都折叠为 {@code \n}，是唯一允许交给模型、也是唯一允许用于精确匹配的表示；</li>
 *   <li><b>原始字节</b>（{@link #rawBytes()}）：提交前并发修改检测的基线，任何写回都必须
 *       以它为前置条件；</li>
 *   <li><b>写回外观</b>（{@link #separatorStyle()} 与 {@link #byteOrderMark()}）：保证
 *       LF 文件写回后仍是 LF、CRLF 文件写回后仍是 CRLF、带 BOM 文件写回后仍带 BOM。</li>
 * </ul>
 *
 * <p>替换只在规范坐标上匹配，但落盘时按原始字节切片：匹配区间之外的字节逐字保留，
 * 因此不会把无关行的分隔符改写成另一种风格。当文件分隔符风格为
 * {@link LineSeparatorStyle#MIXED}（分隔符不一致或含裸 {@code \r}）且本次替换确实需要
 * 合成新的分隔符时，{@link #canReplace(String, String)} 返回 {@code false}，调用方必须
 * 以结构化错误失败关闭，而不是猜测风格。</p>
 *
 * <p>该类型不解析路径、不校验权限、不访问文件系统，也不了解 Tool 协议。</p>
 *
 * @since 0.8.0
 */
public final class WorkspaceTextSnapshot {

    private final String canonicalText;
    private final String rawText;
    private final byte[] rawBytes;
    private final boolean byteOrderMark;
    private final LineSeparatorStyle separatorStyle;
    private final int[] collapsedCarriageReturns;

    WorkspaceTextSnapshot(
            String canonicalText,
            String rawText,
            byte[] rawBytes,
            boolean byteOrderMark,
            LineSeparatorStyle separatorStyle,
            int[] collapsedCarriageReturns) {
        this.canonicalText = Objects.requireNonNull(canonicalText, "canonicalText 不能为空");
        this.rawText = Objects.requireNonNull(rawText, "rawText 不能为空");
        this.rawBytes = Objects.requireNonNull(rawBytes, "rawBytes 不能为空").clone();
        this.byteOrderMark = byteOrderMark;
        this.separatorStyle = Objects.requireNonNull(separatorStyle, "separatorStyle 不能为空");
        this.collapsedCarriageReturns =
                Objects.requireNonNull(collapsedCarriageReturns, "collapsedCarriageReturns 不能为空")
                        .clone();
    }

    /**
     * 返回换行已规范化为 {@code \n} 的模型可见文本。
     *
     * @return 规范文本；空文件返回空字符串
     */
    public String canonicalText() {
        return canonicalText;
    }

    /**
     * 返回原始字节的防御性副本，供提交前冲突检测使用。
     *
     * @return 与磁盘读取瞬间一致的字节副本
     */
    public byte[] rawBytes() {
        return rawBytes.clone();
    }

    /**
     * 指示原始字节是否以 UTF-8 BOM 开头。
     *
     * @return 带 BOM 时为 {@code true}
     */
    public boolean byteOrderMark() {
        return byteOrderMark;
    }

    /**
     * 返回本文件的行分隔符风格。
     *
     * @return 独立命名的分隔符策略
     */
    public LineSeparatorStyle separatorStyle() {
        return separatorStyle;
    }

    /**
     * 统计规范文本中某个片段出现的次数。
     *
     * @param canonicalNeedle 已规范化为 {@code \n} 的查找片段
     * @return 不重叠的出现次数
     */
    public int countOccurrences(String canonicalNeedle) {
        Objects.requireNonNull(canonicalNeedle, "canonicalNeedle 不能为空");
        if (canonicalNeedle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int index = canonicalText.indexOf(canonicalNeedle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + canonicalNeedle.length();
        }
    }

    /**
     * 返回规范文本中首次出现的位置。
     *
     * @param canonicalNeedle 已规范化的查找片段
     * @return 0-based 索引；不存在时为 {@code -1}
     */
    public int indexOf(String canonicalNeedle) {
        return canonicalText.indexOf(Objects.requireNonNull(canonicalNeedle, "canonicalNeedle 不能为空"));
    }

    /**
     * 判断本次替换能否在不改写无关行分隔符的前提下安全落盘。
     *
     * <p>分隔符风格唯一时总是安全。风格为 {@link LineSeparatorStyle#MIXED} 时，只有当
     * 被匹配片段与新片段都不含行分隔符（因此不需要合成任何分隔符）才安全；此时替换
     * 退化为纯字节切片，文件其余部分逐字节保留。</p>
     *
     * @param canonicalOld 已规范化的旧片段
     * @param canonicalNew 已规范化的新片段
     * @return 可安全写回时为 {@code true}
     */
    public boolean canReplace(String canonicalOld, String canonicalNew) {
        Objects.requireNonNull(canonicalOld, "canonicalOld 不能为空");
        Objects.requireNonNull(canonicalNew, "canonicalNew 不能为空");
        if (separatorStyle.writable()) {
            return true;
        }
        return canonicalOld.indexOf('\n') < 0 && canonicalNew.indexOf('\n') < 0;
    }

    /**
     * 在规范坐标上执行精确替换，并返回按原始外观写回的完整字节。
     *
     * <p>匹配区间之外的字节逐字保留；新片段按 {@link #separatorStyle()} 恢复分隔符；
     * 原有 BOM 保留。调用方必须先用 {@link #canReplace(String, String)} 确认安全，
     * 并自行处理“不存在”与“多处匹配”两种前置条件。</p>
     *
     * @param canonicalOld 已规范化的旧片段，必须至少出现一次
     * @param canonicalNew 已规范化的新片段
     * @param all 是否替换全部匹配
     * @return 可直接落盘的完整 UTF-8 字节
     * @throws IllegalStateException 当前风格不允许本次替换时
     * @throws IllegalArgumentException 旧片段为空或不存在时
     */
    public byte[] replaceBytes(String canonicalOld, String canonicalNew, boolean all) {
        String updatedRaw = replaceRawText(canonicalOld, canonicalNew, all);
        byte[] body = updatedRaw.getBytes(StandardCharsets.UTF_8);
        if (!byteOrderMark) {
            return body;
        }
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        return bytes;
    }

    /**
     * 返回替换后的规范文本，供 Read 证据在写入成功后原地更新。
     *
     * @param canonicalOld 已规范化的旧片段
     * @param canonicalNew 已规范化的新片段
     * @param all 是否替换全部匹配
     * @return 替换后的规范文本
     */
    public String replaceCanonicalText(String canonicalOld, String canonicalNew, boolean all) {
        if (all) {
            return canonicalText.replace(canonicalOld, canonicalNew);
        }
        int index = canonicalText.indexOf(canonicalOld);
        if (index < 0) {
            throw new IllegalArgumentException("canonicalOld 在规范文本中不存在");
        }
        return canonicalText.substring(0, index)
                + canonicalNew
                + canonicalText.substring(index + canonicalOld.length());
    }

    /**
     * 返回规范文本中某个位置所在的 1-based 行号。
     *
     * @param canonicalIndex 0-based 规范索引
     * @return 1-based 行号
     */
    public int lineNumberAt(int canonicalIndex) {
        int line = 1;
        int bound = Math.min(canonicalIndex, canonicalText.length());
        for (int index = 0; index < bound; index++) {
            if (canonicalText.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 返回规范文本的逻辑行数；结尾分隔符不额外计入空行。
     *
     * @return 行数；空文件为 0
     */
    public int lineCount() {
        if (canonicalText.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < canonicalText.length(); index++) {
            if (canonicalText.charAt(index) == '\n') {
                lines++;
            }
        }
        if (canonicalText.charAt(canonicalText.length() - 1) == '\n') {
            lines--;
        }
        return lines;
    }

    /**
     * 提取规范文本中一段 1-based 闭区间行，用于生成与 Read 一致的内容证据。
     *
     * <p>返回值使用 {@code \n} 连接，不含结尾分隔符；超出文件的行安静截断，
     * 因此调用方必须自行确认行区间仍然存在。</p>
     *
     * @param firstLine 1-based 起始行
     * @param lastLine 1-based 结束行（含）
     * @return 规范化行片段
     */
    public String canonicalLines(int firstLine, int lastLine) {
        if (firstLine < 1 || lastLine < firstLine || canonicalText.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int line = 1;
        int index = 0;
        int length = canonicalText.length();
        while (index < length && line < firstLine) {
            if (canonicalText.charAt(index) == '\n') {
                line++;
            }
            index++;
        }
        boolean first = true;
        while (index < length && line <= lastLine) {
            int newline = canonicalText.indexOf('\n', index);
            int end = newline < 0 ? length : newline;
            if (!first) {
                builder.append('\n');
            }
            builder.append(canonicalText, index, end);
            first = false;
            if (newline < 0) {
                break;
            }
            index = newline + 1;
            line++;
        }
        return builder.toString();
    }

    private String replaceRawText(String canonicalOld, String canonicalNew, boolean all) {
        if (canonicalOld.isEmpty()) {
            throw new IllegalArgumentException("canonicalOld 不能为空");
        }
        if (!canReplace(canonicalOld, canonicalNew)) {
            throw new IllegalStateException("当前行分隔符风格不允许合成新的分隔符");
        }
        String replacement = separatorStyle == LineSeparatorStyle.CRLF
                ? canonicalNew.replace("\n", "\r\n")
                : canonicalNew;
        StringBuilder builder = new StringBuilder(rawText.length() + replacement.length());
        int canonicalCursor = 0;
        int rawCursor = 0;
        boolean replaced = false;
        while (true) {
            int matchStart = canonicalText.indexOf(canonicalOld, canonicalCursor);
            if (matchStart < 0 || (replaced && !all)) {
                break;
            }
            int matchEnd = matchStart + canonicalOld.length();
            int rawStart = rawOffset(matchStart);
            int rawEnd = rawOffset(matchEnd);
            builder.append(rawText, rawCursor, rawStart).append(replacement);
            canonicalCursor = matchEnd;
            rawCursor = rawEnd;
            replaced = true;
            if (!all) {
                break;
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("canonicalOld 在规范文本中不存在");
        }
        builder.append(rawText, rawCursor, rawText.length());
        return builder.toString();
    }

    /**
     * 把规范索引换算为原始文本索引。
     *
     * <p>原始文本相当于在每个被折叠位置之前插回一个 {@code \r}，因此偏移量等于该位置
     * 之前被折叠的数量。当规范索引本身就是某个 {@code \r\n} 的 {@code \n} 时，返回值
     * 指向那个 {@code \r}，从而让整对分隔符一起参与替换。</p>
     */
    private int rawOffset(int canonicalIndex) {
        int low = 0;
        int high = collapsedCarriageReturns.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (collapsedCarriageReturns[middle] < canonicalIndex) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return canonicalIndex + low;
    }
}
