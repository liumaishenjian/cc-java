package io.github.liumaishenjian.ccjava.tools.local.search;

import java.util.Objects;

/**
 * 已经过 Tool 层类型、范围和交互规则校验的不可变搜索请求。
 *
 * <p>本类型只表达精确搜索语义，不包含 Shell 字符串。{@code protocolRoot} 是已经过
 * WorkspaceGuard 校验的相对路径；Backend 仍不得信任外部进程返回的路径。</p>
 *
 * @param query 字面文本或正则表达式
 * @param protocolRoot 已校验的 Workspace 相对文件或目录
 * @param glob 可选文件 Glob
 * @param fileType 可选 ripgrep 文件类型
 * @param mode 结果模式
 * @param caseSensitive 是否区分大小写
 * @param regex 是否把 query 解释为正则表达式
 * @param multiline 是否允许跨行匹配
 * @param lineNumbers content 模式是否显示行号
 * @param beforeContext content 模式前文行数
 * @param afterContext content 模式后文行数
 * @param offset 跳过的结果条目数
 * @param limit 最大返回条目数；零表示只受总输出上限约束
 * @param cancellation 当前 Run 的取消状态
 * @since 0.3.1
 */
public record TextSearchRequest(
        String query,
        String protocolRoot,
        String glob,
        String fileType,
        TextSearchMode mode,
        boolean caseSensitive,
        boolean regex,
        boolean multiline,
        boolean lineNumbers,
        int beforeContext,
        int afterContext,
        int offset,
        int limit,
        SearchCancellation cancellation) {

    /** 验证 Backend 可以依赖的基本不变量。 */
    public TextSearchRequest {
        query = requireText(query, "query");
        protocolRoot = requireText(protocolRoot, "protocolRoot");
        mode = Objects.requireNonNull(mode, "mode 不能为空");
        cancellation = Objects.requireNonNull(cancellation, "cancellation 不能为空");
        if (glob != null && glob.isBlank()) {
            throw new IllegalArgumentException("glob 不能为空字符串");
        }
        if (fileType != null && fileType.isBlank()) {
            throw new IllegalArgumentException("fileType 不能为空字符串");
        }
        if (beforeContext < 0 || afterContext < 0 || offset < 0 || limit < 0) {
            throw new IllegalArgumentException("搜索范围参数不能为负数");
        }
        if (mode != TextSearchMode.CONTENT
                && (beforeContext != 0 || afterContext != 0 || !lineNumbers)) {
            throw new IllegalArgumentException("非 content 模式不接受上下文或行号控制");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
