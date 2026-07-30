package io.github.liumaishenjian.ccjava.tools.local.search;

import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import java.util.List;

/**
 * 精确文本搜索执行引擎的内部端口。
 *
 * <p>Tool 契约、WorkspaceGuard 和结果裁剪不依赖具体进程实现；普通测试可使用 Fake，
 * 生产装配使用 ripgrep，缺失时由 Tool 选择受限 Java 降级。</p>
 *
 * @since 0.3.1
 */
public interface TextSearchBackend {

    /**
     * 执行已经过 Tool 参数校验的文本搜索。
     *
     * @param query 查询文本或正则表达式
     * @param protocolRoot 相对于 Workspace 的已校验搜索根目录
     * @param glob 可选文件 Glob；没有过滤条件时为 {@code null}
     * @param caseSensitive 是否区分大小写
     * @param regex 是否把 query 解释为正则表达式
     * @return 有界的后端原始结果
     * @throws SearchException 后端不可用、超时、输出超限或执行失败
     */
    SearchResult search(
            String query,
            String protocolRoot,
            String glob,
            boolean caseSensitive,
            boolean regex)
            throws SearchException;

    /**
     * 执行可由 Agent Run 取消的文本搜索。
     *
     * <p>默认实现保持现有 Fake 和非进程后端兼容；进程型实现应覆盖本方法，并在取消后
     * 清理自己启动的全部进程和输出读取任务。</p>
     *
     * @param query 查询文本或正则表达式
     * @param protocolRoot 相对 Workspace 的已校验搜索根目录
     * @param glob 可选文件 Glob
     * @param caseSensitive 是否区分大小写
     * @param regex 是否把 query 解释为正则表达式
     * @param cancellation 当前搜索的取消状态
     * @return 有界的后端原始结果
     * @throws SearchException 搜索取消或后端执行失败
     */
    default SearchResult search(
            String query,
            String protocolRoot,
            String glob,
            boolean caseSensitive,
            boolean regex,
            SearchCancellation cancellation) throws SearchException {
        if (cancellation.isCancellationRequested()) {
            throw new SearchException(ToolError.of(
                    ToolErrorCode.OPERATION_CANCELLED, "文本搜索已取消"));
        }
        return search(query, protocolRoot, glob, caseSensitive, regex);
    }

    /**
     * 执行完整、类型化的搜索请求。
     *
     * <p>旧 Fake Backend 默认只支持语义等价的字面 content 子集；生产 ripgrep
     * Backend 必须覆盖本方法并返回机器协议解析后的类型化结果。</p>
     *
     * @param request 已校验请求
     * @return 类型化 ripgrep 结果
     * @throws SearchException 请求超出后端能力或执行失败
     */
    default RipgrepParsedResult searchStructured(TextSearchRequest request)
            throws SearchException {
        throw new SearchException(ToolError.of(
                ToolErrorCode.SEARCH_UNAVAILABLE, "结构化 ripgrep 搜索能力不可用"));
    }

    /**
     * 后端已经完成进程级字节限制的搜索结果。
     *
     * @param lines 有界输出行
     * @param stderrTruncated 内部诊断是否被裁剪；不得据此向模型暴露原始 stderr
     */
    record SearchResult(List<String> lines, boolean stderrTruncated) {
        /** 创建不可变结果快照。 */
        public SearchResult {
            lines = List.copyOf(lines);
        }
    }

    /** 只携带可安全反馈给模型的结构化错误。 */
    final class SearchException extends Exception {
        /** 已脱敏并可穿过 Tool Pipeline 的错误。 */
        private final ToolError error;

        /**
         * 创建搜索后端异常。
         *
         * @param error 不含命令、绝对路径或原始 stderr 的错误
         */
        public SearchException(ToolError error) {
            super(error.message());
            this.error = error;
        }

        /**
         * 返回可以反馈给 Tool 调用者的结构化错误。
         *
         * @return 安全错误
         */
        public ToolError error() {
            return error;
        }
    }
}
