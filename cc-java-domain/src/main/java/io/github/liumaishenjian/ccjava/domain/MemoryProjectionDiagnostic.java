package io.github.liumaishenjian.ccjava.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 不携带正文、路径或底层异常文本的 M4/M5 诊断。
 *
 * @param kind 原因分类
 * @param topicName 已验证 topic slug；Catalog 级原因时为空
 * @since 0.7.0
 */
public record MemoryProjectionDiagnostic(
        MemoryProjectionDiagnosticKind kind,
        Optional<String> topicName) {

    /** 防御性校验诊断。 */
    public MemoryProjectionDiagnostic {
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        topicName = Objects.requireNonNull(topicName, "topicName 不能为空");
        topicName.ifPresent(name -> {
            if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalArgumentException("topicName 必须是受限 slug");
            }
        });
    }

    /** 创建 Catalog 级诊断。 */
    public static MemoryProjectionDiagnostic catalog(MemoryProjectionDiagnosticKind kind) {
        return new MemoryProjectionDiagnostic(kind, Optional.empty());
    }

    /** 创建 topic 级诊断。 */
    public static MemoryProjectionDiagnostic topic(
            MemoryProjectionDiagnosticKind kind, String name) {
        return new MemoryProjectionDiagnostic(kind, Optional.of(name));
    }
}
