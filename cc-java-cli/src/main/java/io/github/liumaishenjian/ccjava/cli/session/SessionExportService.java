package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.core.session.SessionExportPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 生成稳定、项目自有 Session Export v1；默认只导出 metadata。
 *
 * <p>正文模式要求策略已经确认脱敏。本实现只接受调用方提供的已脱敏语义 record，绝不
 * 直接复制 canonical JSONL 原始行。</p>
 *
 * @since 0.1.0
 */
public final class SessionExportService {
    /** 创建使用稳定 JSON schema 的无状态导出器。 */
    public SessionExportService() { }

    private final JsonMapper mapper = JsonMapper.builder().build();
    /**
     * 根据明确策略生成稳定 Session Export v1 字节。
     *
     * @param sessionId Session identity
     * @param workspaceIdentity 非敏感 Workspace identity
     * @param redactedRecords 调用方已脱敏的语义记录
     * @param policy 本次导出隐私策略
     * @return 稳定 JSON export 字节
     */
    public byte[] export(String sessionId, String workspaceIdentity, java.util.List<String> redactedRecords, SessionExportPolicy policy) {
        Objects.requireNonNull(policy, "policy 不能为空"); ObjectNode root = mapper.createObjectNode();
        root.put("schema", "cc-java-session-export-v1"); root.put("sessionId", safe(sessionId)); root.put("workspaceIdentity", safe(workspaceIdentity)); root.put("contentIncluded", policy.includeContent());
        if (policy.includeContent()) { ArrayNode records = root.putArray("records"); int bytes = 0; for (String record : redactedRecords) { String checked = safeContent(record); bytes += checked.getBytes(StandardCharsets.UTF_8).length; if (bytes > 8 * 1024 * 1024) throw new IllegalArgumentException("export 正文超限"); records.add(checked); } }
        return mapper.writeValueAsBytes(root);
    }
    private static String safe(String value) { Objects.requireNonNull(value, "metadata 不能为空"); if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("metadata 非法"); return value; }
    private static String safeContent(String value) { Objects.requireNonNull(value, "record 不能为空"); if (value.length() > 65_536 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("record 非法"); return value; }
}
