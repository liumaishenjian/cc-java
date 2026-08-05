package io.github.liumaishenjian.ccjava.domain.settings;

/**
 * 单个 Tool 配置在一个已验证 Settings 来源中的整体声明。
 *
 * <p>该声明只表达后续合并所需的“整体替换”或“显式移除”，不执行跨来源解析、合并或
 * Tool 映射。JSON {@code null} 只能由 {@link Removal} 表达，不能出现在替换对象内部。</p>
 *
 * @since 0.8.0
 */
public sealed interface DeclaredToolConfiguration permits DeclaredToolConfiguration.Replacement,
        DeclaredToolConfiguration.Removal {

    /**
     * 以完整标量对象替换同名 Tool 的低优先级配置。
     *
     * @param values 已递归冻结且不含 JSON null 的完整配置对象
     */
    record Replacement(io.github.liumaishenjian.ccjava.domain.JsonObject values)
            implements DeclaredToolConfiguration {
        /** 创建不可为空的整体替换声明。 */
        public Replacement {
            values = java.util.Objects.requireNonNull(values, "values 不能为空");
        }

        @Override
        public String toString() {
            return "Replacement[values=<redacted>]";
        }
    }

    /**
     * 删除低优先级同名 Tool 配置的显式操作。
     *
     * <p>它不携带配置正文，也不表示嵌套对象或标量成员的局部删除。</p>
     */
    record Removal() implements DeclaredToolConfiguration {
        @Override
        public String toString() {
            return "Removal[]";
        }
    }
}
