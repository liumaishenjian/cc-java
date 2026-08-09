package io.github.liumaishenjian.ccjava.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Server 的传输配置。
 *
 * <p>STDIO 使用固定绝对 executable 与结构化 argv；HTTP 只接受 HTTPS 或显式
 * loopback HTTP。认证只保存环境变量名，Secret 值在连接时读取且从不进入该值对象的
 * {@code toString()}。</p>
 *
 * @since 0.10.0
 */
public sealed interface McpTransportConfig permits McpTransportConfig.Stdio, McpTransportConfig.StreamableHttp {

    /**
     * 固定 argv 的 STDIO 传输。
     *
     * @param executable 必须为绝对路径的可执行文件
     * @param arguments 结构化 argv，不经 Shell 拼接
     * @param inheritedEnvironmentNames 允许从父环境复制的变量名
     */
    record Stdio(Path executable, List<String> arguments, List<String> inheritedEnvironmentNames)
            implements McpTransportConfig {
        /** 校验 executable、argv 与环境 allowlist 的数量和长度边界。 */
        public Stdio {
            Path suppliedExecutable = Objects.requireNonNull(executable, "executable 不能为空");
            if (!suppliedExecutable.isAbsolute()) {
                throw new IllegalArgumentException("executable 必须是绝对路径");
            }
            executable = suppliedExecutable.normalize();
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments 不能为空"));
            inheritedEnvironmentNames = List.copyOf(Objects.requireNonNull(
                    inheritedEnvironmentNames, "inheritedEnvironmentNames 不能为空"));
            if (arguments.size() > 64 || inheritedEnvironmentNames.size() > 32
                    || arguments.stream().anyMatch(value -> invalid(value, 16_384))
                    || arguments.stream().mapToInt(String::length).sum() > 30_000
                    || inheritedEnvironmentNames.stream().anyMatch(value -> invalid(value, 128))) {
                throw new IllegalArgumentException("STDIO 参数超过边界");
            }
        }

        @Override
        public String toString() {
            return "Stdio[executable=<redacted>, arguments=" + arguments.size()
                    + ", inheritedEnvironmentNames=" + inheritedEnvironmentNames.size() + "]";
        }
    }

    /**
     * MCP Streamable HTTP 传输。
     *
     * @param endpoint HTTPS 或显式 loopback HTTP endpoint
     * @param bearerTokenEnvironment 可选的 Bearer Token 环境变量名
     */
    record StreamableHttp(URI endpoint, Optional<String> bearerTokenEnvironment)
            implements McpTransportConfig {
        /** 校验 URI、认证变量名与远程 HTTP 安全边界。 */
        public StreamableHttp {
            endpoint = Objects.requireNonNull(endpoint, "endpoint 不能为空").normalize();
            bearerTokenEnvironment = Objects.requireNonNull(
                    bearerTokenEnvironment, "bearerTokenEnvironment 不能为空");
            String scheme = endpoint.getScheme();
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                    && endpoint.getHost() != null
                    && (endpoint.getHost().equalsIgnoreCase("localhost")
                            || endpoint.getHost().equals("127.0.0.1")
                            || endpoint.getHost().equals("::1"));
            if (!("https".equalsIgnoreCase(scheme) || loopbackHttp)
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("HTTP MCP endpoint 必须是 HTTPS 或 loopback HTTP");
            }
            bearerTokenEnvironment.ifPresent(value -> {
                if (invalid(value, 128)) {
                    throw new IllegalArgumentException("认证环境变量名无效");
                }
            });
        }

        @Override
        public String toString() {
            return "StreamableHttp[endpoint=" + endpoint + ", bearerTokenEnvironment="
                    + (bearerTokenEnvironment.isPresent() ? "<configured>" : "<empty>") + "]";
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum
                || value.indexOf('\0') >= 0;
    }
}
