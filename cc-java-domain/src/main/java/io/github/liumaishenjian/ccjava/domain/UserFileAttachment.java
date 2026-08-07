package io.github.liumaishenjian.ccjava.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户显式文件提及在 Run 启动前形成的不可变 UTF-8 快照。
 *
 * <p>该值只携带 Workspace-relative 协议路径和已经安全读取的文本，不携带文件系统或
 * Provider 类型。正文属于不可信模型上下文；digest 只用于快照身份，不授予文件权限。</p>
 *
 * @param protocolPath 使用 {@code /} 分隔的 Workspace-relative 路径
 * @param textSnapshot 所选行的 UTF-8 文本快照
 * @param sha256Digest 快照正文的小写 SHA-256
 * @param startLine 原文件中的 1-based 起始行
 * @param endLine 原文件中的 1-based 结束行
 * @param truncated 是否因行数或字节预算裁剪
 * @since 0.8.1
 */
public record UserFileAttachment(
        String protocolPath,
        String textSnapshot,
        String sha256Digest,
        int startLine,
        int endLine,
        boolean truncated) {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** 校验框架无关附件契约。 */
    public UserFileAttachment {
        protocolPath = Objects.requireNonNull(protocolPath, "protocolPath 不能为空");
        textSnapshot = Objects.requireNonNull(textSnapshot, "textSnapshot 不能为空");
        sha256Digest = Objects.requireNonNull(sha256Digest, "sha256Digest 不能为空");
        String[] segments = protocolPath.split("/", -1);
        boolean unsafeSegment = java.util.Arrays.stream(segments)
                .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
        if (protocolPath.isBlank() || protocolPath.startsWith("/") || protocolPath.startsWith("\\")
                || protocolPath.contains("\\") || protocolPath.matches("^[A-Za-z]:.*")
                || protocolPath.chars().anyMatch(Character::isISOControl) || unsafeSegment) {
            throw new IllegalArgumentException("protocolPath 必须是安全的 Workspace-relative 协议路径");
        }
        if (!DIGEST.matcher(sha256Digest).matches()) {
            throw new IllegalArgumentException("sha256Digest 必须是小写 SHA-256");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("附件行范围无效");
        }
        if (textSnapshot.indexOf('\0') >= 0
                || textSnapshot.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("附件正文超过边界或包含 NUL");
        }
    }
}
