package io.github.liumaishenjian.ccjava.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 将已批准 Markdown Plan 原子交给正常 Agent Runtime 的不可变执行信封。
 *
 * <p>Markdown 按不可信自然语言上下文使用，不解析为 Tool、命令或步骤三元组。该信封只记录
 * 批准事实、权限选择与上下文策略；每次实际 Tool 调用仍必须经过统一 Pipeline。</p>
 *
 * @param planId 已批准工件身份
 * @param sessionId 所属 Session
 * @param approvedRevision 用户实际批准的工件 revision
 * @param contentDigest 已批准 Markdown SHA-256
 * @param markdownSnapshot 已批准完整 Markdown 快照
 * @param originalPermissionMode 进入规划前的权限模式
 * @param effectivePermissionMode 执行阶段恢复的权限模式
 * @param approvalReviewer 执行阶段最终 ASK 的审查主体
 * @param contextPolicy 上下文保留策略
 * @param planningRunId 可用时的规划 Run 定位符
 * @param transcriptLocator 可用时的 canonical transcript 定位符
 * @param userFeedback 用户随决定提交的自然语言反馈
 * @param workspaceDigest 批准时工作区快照摘要
 * @param approvedAt 批准提交时间
 * @since 0.1.0
 */
public record ExecutionBrief(
        String planId,
        SessionId sessionId,
        long approvedRevision,
        String contentDigest,
        String markdownSnapshot,
        PermissionMode originalPermissionMode,
        PermissionMode effectivePermissionMode,
        ApprovalReviewer approvalReviewer,
        PlanContextPolicy contextPolicy,
        Optional<RunId> planningRunId,
        Optional<String> transcriptLocator,
        String userFeedback,
        String workspaceDigest,
        Instant approvedAt) {

    /** 验证工件快照、身份和策略的不可变绑定。 */
    public ExecutionBrief {
        planId = requireText(planId, "planId", 128);
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        if (approvedRevision < 1) throw new IllegalArgumentException("approvedRevision 必须为正数");
        contentDigest = requireText(contentDigest, "contentDigest", 64);
        markdownSnapshot = Objects.requireNonNull(markdownSnapshot, "markdownSnapshot 不能为空");
        if (!contentDigest.equals(PlanArtifact.digest(markdownSnapshot))) {
            throw new IllegalArgumentException("ExecutionBrief 工件摘要不匹配");
        }
        originalPermissionMode = Objects.requireNonNull(originalPermissionMode, "originalPermissionMode 不能为空");
        effectivePermissionMode = Objects.requireNonNull(effectivePermissionMode, "effectivePermissionMode 不能为空");
        if (effectivePermissionMode == PermissionMode.PLAN) {
            throw new IllegalArgumentException("执行阶段不能保持 PLAN 模式");
        }
        approvalReviewer = Objects.requireNonNull(approvalReviewer, "approvalReviewer 不能为空");
        contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy 不能为空");
        planningRunId = Objects.requireNonNull(planningRunId, "planningRunId 不能为空");
        transcriptLocator = Objects.requireNonNull(transcriptLocator, "transcriptLocator 不能为空")
                .map(value -> requireText(value, "transcriptLocator", 256));
        userFeedback = Objects.requireNonNull(userFeedback, "userFeedback 不能为空");
        if (userFeedback.codePointCount(0, userFeedback.length()) > 8_192 || userFeedback.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("userFeedback 无效");
        }
        workspaceDigest = requireText(workspaceDigest, "workspaceDigest", 256);
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt 不能为空");
    }


    /**
     * 计算不含 Secret/正文副本的 canonical brief 摘要，供 EvidenceLedger 固定身份。
     *
     * @return 小写 SHA-256
     */
    public String evidenceBindingDigest() {
        String canonical = String.join("\n", planId, sessionId.value(), Long.toString(approvedRevision),
                contentDigest, originalPermissionMode.name(), effectivePermissionMode.name(),
                approvalReviewer.name(), contextPolicy.name(), planningRunId.map(RunId::value).orElse(""),
                transcriptLocator.orElse(""), PlanArtifact.digest(userFeedback), workspaceDigest,
                approvedAt.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String requireText(String value, String name, int max) {
        value = Objects.requireNonNull(value, name + " 不能为空");
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
