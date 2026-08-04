package io.github.liumaishenjian.ccjava.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 M1/M2 mutation 的不可变结果。
 *
 * <p>M1 成功不会因后续 Index 失败而伪装成整体回滚：状态仍是 CREATED、UPDATED 或 DELETED，
 * 并附带唯一的 {@link MemoryMutationDiagnosticKind#INDEX_REBUILD_FAILED}。REJECTED 表示 M1
 * 没有由本次调用提交。</p>
 *
 * @param status mutation 终态
 * @param topic create/update 成功后的持久 topic；其他状态为空
 * @param diagnostics 隐私安全诊断
 * @since 0.7.0
 */
public record MemoryMutationResult(
        MemoryMutationStatus status,
        Optional<MemoryTopic> topic,
        List<MemoryMutationDiagnostic> diagnostics) {

    /** 强制成功、拒绝和 Index 失败之间的状态不变量。 */
    public MemoryMutationResult {
        status = Objects.requireNonNull(status, "status 不能为空");
        topic = Objects.requireNonNull(topic, "topic 不能为空");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
        boolean topicRequired = status == MemoryMutationStatus.CREATED
                || status == MemoryMutationStatus.UPDATED;
        if (topicRequired != topic.isPresent()) {
            throw new IllegalArgumentException("只有 CREATED/UPDATED 必须携带持久 topic");
        }
        topic.ifPresent(value -> {
            if (value.contentDigest().isEmpty()) {
                throw new IllegalArgumentException("成功结果中的 topic 必须携带持久摘要");
            }
        });
        if (status == MemoryMutationStatus.REJECTED) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("REJECTED 必须携带诊断");
            }
        } else if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.kind()
                != MemoryMutationDiagnosticKind.INDEX_REBUILD_FAILED)) {
            throw new IllegalArgumentException("成功结果只能携带 INDEX_REBUILD_FAILED");
        }
        if (status == MemoryMutationStatus.INDEX_REBUILT && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("INDEX_REBUILT 不能携带失败诊断");
        }
    }

    /** 创建 M1 成功结果。 */
    public static MemoryMutationResult saved(
            MemoryMutationStatus status,
            MemoryTopic topic,
            boolean indexRebuildFailed) {
        if (status != MemoryMutationStatus.CREATED && status != MemoryMutationStatus.UPDATED) {
            throw new IllegalArgumentException("saved 只接受 CREATED 或 UPDATED");
        }
        return new MemoryMutationResult(
                status,
                Optional.of(topic),
                indexRebuildFailed
                        ? List.of(MemoryMutationDiagnostic.topic(
                                MemoryMutationDiagnosticKind.INDEX_REBUILD_FAILED,
                                topic.name()))
                        : List.of());
    }

    /** 创建删除成功结果。 */
    public static MemoryMutationResult deleted(String topicName, boolean indexRebuildFailed) {
        return new MemoryMutationResult(
                MemoryMutationStatus.DELETED,
                Optional.empty(),
                indexRebuildFailed
                        ? List.of(MemoryMutationDiagnostic.topic(
                                MemoryMutationDiagnosticKind.INDEX_REBUILD_FAILED,
                                topicName))
                        : List.of());
    }

    /** 创建 M2 重建成功结果。 */
    public static MemoryMutationResult indexRebuilt() {
        return new MemoryMutationResult(
                MemoryMutationStatus.INDEX_REBUILT,
                Optional.empty(),
                List.of());
    }

    /** 创建在 M1 提交前结束的拒绝结果。 */
    public static MemoryMutationResult rejected(MemoryMutationDiagnostic diagnostic) {
        return new MemoryMutationResult(
                MemoryMutationStatus.REJECTED,
                Optional.empty(),
                List.of(Objects.requireNonNull(diagnostic, "diagnostic 不能为空")));
    }
}
