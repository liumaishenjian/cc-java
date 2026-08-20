package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.domain.PlanEvidenceKind;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceLedger;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceReference;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceRequirement;
import io.github.liumaishenjian.ccjava.domain.PlanEvidenceStatus;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 在 CLI 边缘严格编码 PlanEvidenceLedger；不把 Jackson 泄漏进 Domain。 */
final class PlanEvidenceLedgerJson {
    private PlanEvidenceLedgerJson() { }

    static ObjectNode encode(ObjectNode node, PlanEvidenceLedger ledger) {
        node.put("sessionId", ledger.sessionId().value()); node.put("planId", ledger.planId());
        node.put("approvedPlanRevision", ledger.approvedPlanRevision());
        node.put("executionBriefDigest", ledger.executionBriefDigest());
        node.put("approvedWorkspaceDigest", ledger.approvedWorkspaceDigest());
        node.put("createdAt", ledger.createdAt().toString()); node.put("updatedAt", ledger.updatedAt().toString());
        ArrayNode requirements = node.putArray("requirements");
        ledger.requirements().forEach(item -> { ObjectNode value = requirements.addObject();
            value.put("requirementId", item.requirementId()); value.put("kind", item.kind().name());
            value.put("locator", item.locator()); value.put("label", item.label()); value.put("required", item.required()); });
        ArrayNode references = node.putArray("references");
        ledger.references().forEach(item -> { ObjectNode value = references.addObject();
            value.put("requirementId", item.requirementId()); value.put("status", item.status().name());
            value.put("sourceType", item.sourceType()); value.put("sourceReference", item.sourceReference());
            item.contentDigest().ifPresent(digest -> value.put("contentDigest", digest));
            value.put("reasonCode", item.reasonCode()); value.put("recordedAt", item.recordedAt().toString()); });
        return node;
    }

    static PlanEvidenceLedger decode(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("EvidenceLedger 缺失");
        ArrayList<PlanEvidenceRequirement> requirements = new ArrayList<>();
        JsonNode requirementArray = node.get("requirements");
        if (requirementArray == null || !requirementArray.isArray()
                || requirementArray.size() > PlanEvidenceLedger.MAX_REQUIREMENTS) throw new IllegalArgumentException("requirements 无效");
        for (JsonNode item : requirementArray) requirements.add(new PlanEvidenceRequirement(text(item, "requirementId"),
                PlanEvidenceKind.valueOf(text(item, "kind")), text(item, "locator"), text(item, "label"), bool(item, "required")));
        ArrayList<PlanEvidenceReference> references = new ArrayList<>();
        JsonNode referenceArray = node.get("references");
        if (referenceArray == null || !referenceArray.isArray()
                || referenceArray.size() > PlanEvidenceLedger.MAX_REQUIREMENTS) throw new IllegalArgumentException("references 无效");
        for (JsonNode item : referenceArray) references.add(new PlanEvidenceReference(text(item, "requirementId"),
                PlanEvidenceStatus.valueOf(text(item, "status")), text(item, "sourceType"),
                text(item, "sourceReference"), optionalText(item, "contentDigest"), text(item, "reasonCode"),
                Instant.parse(text(item, "recordedAt"))));
        return new PlanEvidenceLedger(new SessionId(text(node, "sessionId")), text(node, "planId"),
                nonNegativeLong(node, "approvedPlanRevision"), text(node, "executionBriefDigest"),
                text(node, "approvedWorkspaceDigest"), requirements, references,
                Instant.parse(text(node, "createdAt")), Instant.parse(text(node, "updatedAt")));
    }

    private static String text(JsonNode node, String field) { JsonNode value=node.get(field);
        if (value==null || !value.isString()) throw new IllegalArgumentException(field+" 无效"); return value.stringValue(); }
    private static Optional<String> optionalText(JsonNode node, String field) { JsonNode value=node.get(field);
        if (value==null) return Optional.empty(); if (!value.isString()) throw new IllegalArgumentException(field+" 无效"); return Optional.of(value.stringValue()); }
    private static long nonNegativeLong(JsonNode node, String field) { JsonNode value=node.get(field);
        if (value==null || !value.isIntegralNumber() || value.longValue()<0) throw new IllegalArgumentException(field+" 无效"); return value.longValue(); }
    private static boolean bool(JsonNode node, String field) { JsonNode value=node.get(field);
        if (value==null || !value.isBoolean()) throw new IllegalArgumentException(field+" 无效"); return value.booleanValue(); }
}
