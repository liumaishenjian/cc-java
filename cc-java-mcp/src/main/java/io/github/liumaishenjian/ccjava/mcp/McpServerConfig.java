package io.github.liumaishenjian.ccjava.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 一个经过 Settings/Trust Gate 的 MCP Server 配置快照。
 *
 * @param name 用于 Tool 命名空间的稳定 Server 名称
 * @param transport 传输配置
 * @param allowTools 空列表表示允许所有未被 deny 的 Tool
 * @param denyTools 总是优先的 Tool 拒绝列表
 * @param requestTimeout 单次协议请求上限
 * @param trusted 是否已经通过来源与指纹信任 Gate
 * @since 0.10.0
 */
public record McpServerConfig(
        String name,
        McpTransportConfig transport,
        List<String> allowTools,
        List<String> denyTools,
        Duration requestTimeout,
        boolean trusted) {

    /** 校验名称、过滤列表、Transport 和请求上限。 */
    public McpServerConfig {
        name = requireName(name, "name");
        transport = Objects.requireNonNull(transport, "transport 不能为空");
        allowTools = checkedNames(allowTools, "allowTools");
        denyTools = checkedNames(denyTools, "denyTools");
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("requestTimeout 必须在 1ms 到 2min 之间");
        }
    }

    /**
     * 返回 Tool 是否通过 deny-first 的发现过滤。
     *
     * @param toolName 远端 Tool 原始名称
     * @return 未被 deny 且命中空 allowlist 或显式 allowlist 时为 {@code true}
     */
    public boolean includes(String toolName) {
        String checked = requireName(toolName, "toolName");
        return !denyTools.contains(checked) && (allowTools.isEmpty() || allowTools.contains(checked));
    }

    private static List<String> checkedNames(List<String> values, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " 不能为空"));
        if (copy.size() > 256) {
            throw new IllegalArgumentException(field + " 数量超过上限");
        }
        copy.forEach(value -> requireName(value, field));
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " 不能重复");
        }
        return copy;
    }

    static String requireName(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(field + " 格式无效");
        }
        return value;
    }
}
