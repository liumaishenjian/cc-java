package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 不回显候选正文、Secret 或本地路径的 Memory mutation 诊断。
 *
 * @param kind 诊断分类
 * @param topicName 已验证 topic slug；根级或未验证输入失败时为空
 * @since 0.7.0
 */
public record MemoryMutationDiagnostic(
        MemoryMutationDiagnosticKind kind,
        Optional<String> topicName) {

    /** 校验可选 topic 只能携带已经验证的 slug。 */
    public MemoryMutationDiagnostic {
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

    /** 创建关联已验证 topic 的诊断。 */
    public static MemoryMutationDiagnostic topic(
            MemoryMutationDiagnosticKind kind,
            String topicName) {
        return new MemoryMutationDiagnostic(kind, Optional.of(topicName));
    }

    /** 创建不关联 topic 的诊断。 */
    public static MemoryMutationDiagnostic repository(MemoryMutationDiagnosticKind kind) {
        return new MemoryMutationDiagnostic(kind, Optional.empty());
    }
}
