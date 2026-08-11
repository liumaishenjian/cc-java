package io.github.liumaishenjian.ccjava.mcp;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 为 Plugin provider 引用的实际 S10 MCP 配置计算 privacy-safe canonical SHA-256。
 *
 * <p>摘要稳定排序 Server，并绑定 transport 类型、可执行文件规范绝对路径字节、结构化 argv、
 * 环境变量名 allowlist、HTTP endpoint、Bearer 环境变量名、allow/deny filter、timeout 与 trust。
 * 原值只进入单向摘要，不进入返回值、异常或日志；Secret 值从不属于 {@link McpServerConfig}。</p>
 *
 * @since 0.11.0
 */
public final class McpPluginConfigDigest {
    private McpPluginConfigDigest() { }

    /**
     * 计算与输入顺序无关的 MCP Plugin 配置摘要。
     *
     * @param configs manifest 实际引用的宿主 MCP 配置
     * @return 64 字符 lowercase SHA-256
     */
    public static String compute(List<McpServerConfig> configs) {
        try {
            List<McpServerConfig> ordered = List.copyOf(Objects.requireNonNull(configs, "configs 不能为空"))
                    .stream().sorted(Comparator.comparing(McpServerConfig::name)).toList();
            if (ordered.stream().map(McpServerConfig::name).distinct().count() != ordered.size()) {
                throw new IllegalArgumentException("重复 MCP config");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, "mcp-plugin-config-v1");
            integer(digest, ordered.size());
            for (McpServerConfig config : ordered) {
                field(digest, config.name());
                field(digest, Boolean.toString(config.trusted()));
                field(digest, Long.toString(config.requestTimeout().toNanos()));
                strings(digest, config.allowTools());
                strings(digest, config.denyTools());
                if (config.transport() instanceof McpTransportConfig.Stdio stdio) {
                    field(digest, "stdio");
                    field(digest, stdio.executable().toAbsolutePath().normalize().toString());
                    strings(digest, stdio.arguments());
                    strings(digest, stdio.inheritedEnvironmentNames());
                } else if (config.transport() instanceof McpTransportConfig.StreamableHttp http) {
                    field(digest, "streamable-http");
                    URI endpoint = http.endpoint().normalize();
                    field(digest, endpoint.toASCIIString());
                    field(digest, http.bearerTokenEnvironment().orElse(""));
                } else {
                    throw new IllegalArgumentException("未知 MCP transport");
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MCP config digest 无法计算");
        }
    }

    private static void strings(MessageDigest digest, List<String> values) {
        integer(digest, values.size());
        values.forEach(value -> field(digest, value));
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
