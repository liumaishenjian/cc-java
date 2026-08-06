package io.github.liumaishenjian.ccjava.cli.instructions;

import java.util.List;
import java.util.Objects;

/**
 * 已发布 Instructions 的只读 doctor 投影。
 *
 * <p>该类型只保存来源类别、安全 ID 和固定诊断，不保存指令正文、digest、物理路径或发现请求。</p>
 *
 * @param revision 最近完整投影的安全 revision
 * @param sources 已发布来源的安全摘要
 * @param diagnostics 已发布的固定诊断
 * @since 0.8.0
 */
public record InstructionDoctorSnapshot(String revision, List<Source> sources, List<Diagnostic> diagnostics) {
    /** 冻结无正文的诊断快照。 */
    public InstructionDoctorSnapshot {
        revision = Objects.requireNonNull(revision, "revision 不能为空");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources 不能为空"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
    }

    /**
     * 已发布指令来源的安全摘要。
     *
     * @param sourceKind 固定来源类别
     * @param safeId 非绝对路径的安全来源标识
     */
    public record Source(String sourceKind, String safeId) {
        /**
         * 冻结已发布来源的安全字段。
         *
         * @param sourceKind 固定来源类别
         * @param safeId 安全来源标识
         */
        public Source {
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
            safeId = Objects.requireNonNull(safeId, "safeId 不能为空");
        }
    }

    /**
     * 已发布指令固定诊断的安全摘要。
     *
     * @param sourceKind 固定来源类别
     * @param safeId 非绝对路径的安全来源标识
     * @param code 固定诊断代码
     * @param severity 固定严重程度
     */
    public record Diagnostic(String sourceKind, String safeId, String code, String severity) {
        /**
         * 冻结已发布诊断的安全字段。
         *
         * @param sourceKind 固定来源类别
         * @param safeId 安全来源标识
         * @param code 固定诊断代码
         * @param severity 固定严重程度
         */
        public Diagnostic {
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind 不能为空");
            safeId = Objects.requireNonNull(safeId, "safeId 不能为空");
            code = Objects.requireNonNull(code, "code 不能为空");
            severity = Objects.requireNonNull(severity, "severity 不能为空");
        }
    }
}
