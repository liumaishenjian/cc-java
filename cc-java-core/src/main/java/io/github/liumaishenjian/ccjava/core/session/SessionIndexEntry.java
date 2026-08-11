package io.github.liumaishenjian.ccjava.core.session;

import java.time.Instant;
import java.util.Objects;

/**
 * Session 派生索引中的 metadata-only 条目。
 *
 * @param sessionId Session ID
 * @param workspaceIdentity 不含绝对路径的 workspace fingerprint
 * @param displayName 有界安全显示名
 * @param updatedAt 更新时间
 * @param status 固定生命周期状态
 * @since 0.1.0
 */
public record SessionIndexEntry(String sessionId, String workspaceIdentity, String displayName, Instant updatedAt, SessionLifecycleStatus status) {
    /** 校验所有 metadata 字段有界且生命周期状态完整。 */
    public SessionIndexEntry {
        sessionId = text(sessionId, "sessionId", 128); workspaceIdentity = text(workspaceIdentity, "workspaceIdentity", 128); displayName = text(displayName, "displayName", 256);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空"); status = Objects.requireNonNull(status, "status 不能为空");
    }
    private static String text(String value, String field, int max) { Objects.requireNonNull(value, field + " 不能为空"); if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " 非法"); return value; }
}
