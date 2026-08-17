package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.domain.PlanDocument;
import io.github.liumaishenjian.ccjava.domain.PlanStatus;
import io.github.liumaishenjian.ccjava.domain.PlanStep;
import io.github.liumaishenjian.ccjava.domain.PlanStepAction;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把模型最终文本严格解析为项目自有 {@link PlanDocument}。
 *
 * <p>解析器位于 Headless Adapter 边缘，不改变 Agent transcript，也不信任模型提供的 ID、
 * 状态、摘要或步骤序号。调用方提供的 Run ID 与工作区摘要是唯一可信身份来源；未知字段、
 * Markdown 包裹、超限、控制字符和非连续结构均失败关闭。</p>
 *
 * @since 0.1.0
 */
final class PlanProposalParser {
    static final int MAX_PROPOSAL_BYTES = 64 * 1024;
    static final int MAX_OBJECTIVE_CODE_POINTS = 8_000;
    static final int MAX_STEPS = 128;
    static final int MAX_TITLE_CODE_POINTS = 200;
    static final int MAX_DETAIL_CODE_POINTS = 8_000;
    private static final Set<String> ROOT_FIELDS = Set.of("objective", "steps");
    private static final Set<String> STEP_FIELDS = Set.of("title", "detail", "action");
    private static final Set<String> ACTION_FIELDS = Set.of("toolName", "arguments", "safePreview");

    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_PROPOSAL_BYTES)
                    .maxNestingDepth(4)
                    .maxStringLength(MAX_DETAIL_CODE_POINTS)
                    .maxNameLength(64)
                    .maxTokenCount(1_024)
                    .build())
            .build()).build();

    /**
     * 解析并规范化单个模型 Plan proposal。
     *
     * @param text 模型回合的完整最终文本
     * @param planId Runtime 生成的安全计划 ID
     * @param workspaceDigest Runtime 获取的当前工作区摘要
     * @return 状态为 {@link PlanStatus#DRAFT} 的规范计划
     * @throws IllegalArgumentException proposal 不满足严格、有界契约时
     */
    PlanDocument parse(String text, String planId, String workspaceDigest) {
        Objects.requireNonNull(text, "text 不能为空");
        if (text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > MAX_PROPOSAL_BYTES) {
            throw new IllegalArgumentException("Plan proposal 为空或超过上限");
        }
        try {
            JsonNode root = json.readTree(text);
            requireObject(root, "proposal");
            requireExactFields(root, ROOT_FIELDS, "proposal");
            String objective = requiredText(root.get("objective"), "objective", MAX_OBJECTIVE_CODE_POINTS);
            JsonNode rawSteps = root.get("steps");
            if (rawSteps == null || !rawSteps.isArray() || rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS) {
                throw new IllegalArgumentException("steps 数量无效");
            }
            List<PlanStep> steps = new ArrayList<>(rawSteps.size());
            for (int index = 0; index < rawSteps.size(); index++) {
                JsonNode rawStep = rawSteps.get(index);
                requireObject(rawStep, "step");
                requireStepFields(rawStep);
                JsonNode rawAction = rawStep.get("action");
                if (rawAction == null) {
                    steps.add(new PlanStep(index + 1,
                            requiredText(rawStep.get("title"), "title", MAX_TITLE_CODE_POINTS),
                            requiredText(rawStep.get("detail"), "detail", MAX_DETAIL_CODE_POINTS),
                            workspaceDigest));
                    continue;
                }
                requireObject(rawAction, "action");
                requireExactFields(rawAction, ACTION_FIELDS, "action");
                String toolName = requiredText(rawAction.get("toolName"), "toolName", 64);
                if (!PlanStepAction.allowedToolNames().contains(toolName)) throw new IllegalArgumentException("Tool 不允许");
                if (!rawAction.get("arguments").isObject()) throw new IllegalArgumentException("arguments 必须是 Object");
                java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
                rawAction.get("arguments").propertyStream().forEach(entry -> {
                    JsonNode value = entry.getValue();
                    if (value.isTextual()) args.put(entry.getKey(), value.textValue());
                    else if (value.isBoolean()) args.put(entry.getKey(), value.booleanValue());
                    else if (value.isIntegralNumber()) args.put(entry.getKey(), value.longValue());
                    else throw new IllegalArgumentException("arguments 只能是 bounded JSON scalar");
                });
                PlanStepAction action = new PlanStepAction(toolName, new JsonObject(args),
                        requiredText(rawAction.get("safePreview"), "safePreview", 1_000));
                steps.add(new PlanStep(index + 1,
                        requiredText(rawStep.get("title"), "title", MAX_TITLE_CODE_POINTS),
                        requiredText(rawStep.get("detail"), "detail", MAX_DETAIL_CODE_POINTS),
                        workspaceDigest, action));
            }
            return new PlanDocument(planId, objective, steps, PlanStatus.DRAFT, workspaceDigest);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Plan proposal JSON 无效", failure);
        }
    }

    private static void requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(name + " 必须是 Object");
    }

    private static void requireStepFields(JsonNode node) {
        HashSet<String> fields = new HashSet<>();
        node.propertyStream().forEach(entry -> fields.add(entry.getKey()));
        if (fields.equals(Set.of("title", "detail"))) return;
        if (!fields.equals(STEP_FIELDS)) throw new IllegalArgumentException("step 字段无效");
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String name) {
        HashSet<String> fields = new HashSet<>();
        node.propertyStream().forEach(entry -> fields.add(entry.getKey()));
        if (!fields.equals(expected)) throw new IllegalArgumentException(name + " 字段无效");
    }

    private static String requiredText(JsonNode node, String name, int maximumCodePoints) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException(name + " 必须是字符串");
        String value = node.textValue().strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maximumCodePoints
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " 无效");
        }
        return value;
    }
}
