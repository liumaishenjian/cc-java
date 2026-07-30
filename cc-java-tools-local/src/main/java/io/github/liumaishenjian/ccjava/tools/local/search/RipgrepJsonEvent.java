package io.github.liumaishenjian.ccjava.tools.local.search;

import java.util.List;

/**
 * ripgrep {@code --json} JSON Lines 中已经通过语法和字段校验的事件。
 *
 * <p>事件中的路径仍是外部进程提供的不可信文本。本类型只保证 JSON 结构正确，不负责确认路径属于
 * Workspace；调用方必须在读取或展示结果前再次经过 WorkspaceGuard。</p>
 *
 * @since 0.3.1
 */
public sealed interface RipgrepJsonEvent
        permits RipgrepJsonEvent.FileBoundary, RipgrepJsonEvent.SearchLine, RipgrepJsonEvent.Summary {

    /**
     * 文件扫描边界。
     *
     * @param kind 开始或结束
     * @param path ripgrep 返回的原始路径
     */
    record FileBoundary(BoundaryKind kind, String path) implements RipgrepJsonEvent {
        /** 创建不可变边界事件。 */
        public FileBoundary {
            if (kind == null || path == null || path.isBlank()) {
                throw new IllegalArgumentException("文件边界必须包含类型和路径");
            }
        }
    }

    /**
     * 内容匹配或上下文行。
     *
     * @param kind 匹配或上下文
     * @param path ripgrep 返回的原始路径
     * @param lineNumber 一基行号；缺失时为 {@code 0}
     * @param absoluteOffset 文件内的零基字节偏移；缺失时为 {@code -1}
     * @param text 原始行文本，可以包含换行以表示多行匹配
     * @param submatches UTF-8 文本中的字节区间；上下文事件通常为空
     */
    record SearchLine(
            LineKind kind,
            String path,
            long lineNumber,
            long absoluteOffset,
            String text,
            List<Submatch> submatches)
            implements RipgrepJsonEvent {
        /** 创建不可变搜索行并验证不会产生负长度区间。 */
        public SearchLine {
            if (kind == null || path == null || path.isBlank() || text == null) {
                throw new IllegalArgumentException("搜索行必须包含类型、路径和文本");
            }
            if (lineNumber < 0 || absoluteOffset < -1) {
                throw new IllegalArgumentException("搜索行位置非法");
            }
            submatches = List.copyOf(submatches);
        }
    }

    /**
     * 单个匹配区间。
     *
     * @param start UTF-8 行文本中的起始字节偏移
     * @param end UTF-8 行文本中的结束字节偏移，不包含该位置
     * @param text ripgrep 给出的匹配文本
     */
    record Submatch(long start, long end, String text) {
        /** 验证区间有序且匹配文本存在。 */
        public Submatch {
            if (start < 0 || end < start || text == null) {
                throw new IllegalArgumentException("匹配区间非法");
            }
        }
    }

    /**
     * 搜索汇总事件。
     *
     * @param matchedLines ripgrep 统计的匹配行数；字段不可用时为 {@code -1}
     * @param matches ripgrep 统计的子匹配数；字段不可用时为 {@code -1}
     */
    record Summary(long matchedLines, long matches) implements RipgrepJsonEvent {
        /** 汇总值只允许使用非负数或 {@code -1} 表示未知。 */
        public Summary {
            if (matchedLines < -1 || matches < -1) {
                throw new IllegalArgumentException("汇总值非法");
            }
        }
    }

    /** 文件扫描边界类型。 */
    enum BoundaryKind {
        /** 开始扫描一个文件。 */
        BEGIN,
        /** 完成扫描一个文件。 */
        END
    }

    /** 搜索内容事件类型。 */
    enum LineKind {
        /** 实际命中查询条件的内容。 */
        MATCH,
        /** 因 before/after/context 参数附带返回的上下文。 */
        CONTEXT
    }
}
