package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 单个 topic 或 Catalog 层级的隐私安全诊断。
 *
 * <p>{@code topicName} 只能是已经验证的 slug；非法文件名和路径不得复制到该字段。</p>
 *
 * @param kind 诊断分类
 * @param topicName 已验证 topic 名称；根级或未验证输入失败时为空
 * @since 0.7.0
 */
public record MemoryDiagnostic(
        MemoryDiagnosticKind kind,
        Optional<String> topicName) {

    /** 校验诊断分类和可选 topic。 */
    public MemoryDiagnostic {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        topicName = Objects.requireNonNull(topicName, "topicName 不能为空")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (topicName.isPresent()
                && (topicName.get().codePointCount(0, topicName.get().length()) > 64
                        || !topicName.get().matches("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
            throw new IllegalArgumentException("topicName 必须是受限 kebab-case slug");
        }
    }

    /**
     * 创建不关联 topic 的 Catalog 级诊断。
     *
     * @param kind 不回显底层异常文本的 Catalog 失败分类
     * @return 不携带 topic slug 的隐私安全诊断
     */
    public static MemoryDiagnostic catalog(MemoryDiagnosticKind kind) {
        return new MemoryDiagnostic(kind, Optional.empty());
    }

    /**
     * 创建关联已验证 topic 的诊断。
     *
     * @param kind 不回显正文、路径或异常文本的失败分类
     * @param topicName 已验证的受限 topic slug
     * @return 仅携带安全 slug 的 topic 级诊断
     */
    public static MemoryDiagnostic topic(
            MemoryDiagnosticKind kind,
            String topicName) {
        return new MemoryDiagnostic(kind, Optional.of(topicName));
    }
}
