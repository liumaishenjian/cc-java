package io.github.liumaishenjian.ccjava.tools.local.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从完整 ripgrep JSON Lines 流聚合出的内部搜索结果。
 *
 * <p>该模型同时保留 content、files 和 count 三种视图，避免上层通过重新解析带冒号的文本行来推导
 * 路径或数量。路径仍必须由 WorkspaceGuard 复验。</p>
 *
 * @param content 按进程输出顺序排列的匹配和上下文事件
 * @param files 至少产生一个匹配的文件，按首次命中顺序去重
 * @param counts 每个文件的匹配事件数，按首次命中顺序排列
 * @param summary 可选的进程汇总
 * @param ignoredEvents 被安全忽略的未知或字段不完整事件数
 * @since 0.3.1
 */
public record RipgrepParsedResult(
        List<RipgrepJsonEvent.SearchLine> content,
        List<String> files,
        Map<String, Long> counts,
        RipgrepJsonEvent.Summary summary,
        int ignoredEvents) {

    /** 创建不可变聚合结果。 */
    public RipgrepParsedResult {
        content = List.copyOf(content);
        files = List.copyOf(files);
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        if (ignoredEvents < 0) {
            throw new IllegalArgumentException("忽略事件数不能为负数");
        }
    }
}
