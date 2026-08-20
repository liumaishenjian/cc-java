package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.domain.ApprovalReviewer;
import io.github.liumaishenjian.ccjava.domain.ExecutionBrief;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.PlanContextPolicy;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 在 CLI 持久边缘编码和严格解码 ExecutionBrief；Jackson 类型不进入 Domain。 */
final class ExecutionBriefJson {
    private ExecutionBriefJson() { }

    static ObjectNode encode(ObjectNode node, ExecutionBrief brief) {
        node.put("planId", brief.planId());
        node.put("sessionId", brief.sessionId().value());
        node.put("approvedRevision", brief.approvedRevision());
        node.put("contentDigest", brief.contentDigest());
        node.put("originalPermissionMode", brief.originalPermissionMode().name());
        node.put("effectivePermissionMode", brief.effectivePermissionMode().name());
        node.put("approvalReviewer", brief.approvalReviewer().name());
        node.put("contextPolicy", brief.contextPolicy().name());
        brief.planningRunId().ifPresent(value -> node.put("planningRunId", value.value()));
        brief.transcriptLocator().ifPresent(value -> node.put("transcriptLocator", value));
        node.put("userFeedback", brief.userFeedback());
        node.put("workspaceDigest", brief.workspaceDigest());
        node.put("approvedAt", brief.approvedAt().toString());
        return node;
    }

    static ExecutionBrief decode(JsonNode node, String markdownSnapshot) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("ExecutionBrief 缺失");
        return new ExecutionBrief(text(node, "planId"), new SessionId(text(node, "sessionId")),
                positiveLong(node, "approvedRevision"), text(node, "contentDigest"),
                markdownSnapshot, enumValue(PermissionMode.class, node, "originalPermissionMode"),
                enumValue(PermissionMode.class, node, "effectivePermissionMode"),
                enumValue(ApprovalReviewer.class, node, "approvalReviewer"),
                enumValue(PlanContextPolicy.class, node, "contextPolicy"),
                optionalText(node, "planningRunId").map(RunId::new), optionalText(node, "transcriptLocator"),
                node.has("userFeedback") && node.get("userFeedback").isString()
                        ? node.get("userFeedback").stringValue() : "",
                text(node, "workspaceDigest"), Instant.parse(text(node, "approvedAt")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException(field + " 无效");
        return value.stringValue();
    }
    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return Optional.empty();
        if (!value.isString()) throw new IllegalArgumentException(field + " 无效");
        return Optional.of(value.stringValue());
    }
    private static long positiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 1) {
            throw new IllegalArgumentException(field + " 无效");
        }
        return value.longValue();
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String field) {
        return Enum.valueOf(type, text(node, field));
    }
}
