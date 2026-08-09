package io.github.liumaishenjian.ccjava.cli.extensions;

import java.util.Objects;
import java.util.Optional;

/**
 * 不包含路径、命令、端点、Secret 或异常正文的 Extension 诊断。
 *
 * @param userLoaded user 配置是否成功加载
 * @param projectPresent project 配置是否存在
 * @param projectTrusted project 配置摘要是否已批准
 * @param hookCount 已激活 Hook 数量
 * @param mcpServerCount 已解析 MCP Server 数量
 * @param projectFingerprint 可供显式批准的安全摘要
 * @param diagnosticCode 可选固定诊断码
 */
public record ExtensionStatus(
        boolean userLoaded,
        boolean projectPresent,
        boolean projectTrusted,
        int hookCount,
        int mcpServerCount,
        Optional<String> projectFingerprint,
        Optional<String> diagnosticCode) {
    /** 校验可选诊断字段不为 {@code null}。 */
    public ExtensionStatus {
        projectFingerprint = Objects.requireNonNull(projectFingerprint, "projectFingerprint 不能为空");
        diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode 不能为空");
    }
    static ExtensionStatus empty() {
        return new ExtensionStatus(false, false, false, 0, 0, Optional.empty(), Optional.empty());
    }
}
