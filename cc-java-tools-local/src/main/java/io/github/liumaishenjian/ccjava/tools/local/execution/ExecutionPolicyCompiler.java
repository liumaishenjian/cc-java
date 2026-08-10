package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.domain.execution.ExecutionPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.FileAccessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.NetworkPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.ProcessPolicy;
import io.github.liumaishenjian.ccjava.domain.execution.SecretPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将 S13 当前可表达的策略子集编译为平台执行前置条件。
 *
 * <p>当前只接受精确 Workspace 作为唯一可读、可写根，固定控制面 carve-out、deny-all
 * 网络、禁止 detach/提权的进程策略，以及不含 Secret 的小型显式环境。任何额外根、网络
 * allowlist、未知控制面或未知 Secret 集合都在进程启动前失败关闭。</p>
 *
 * @since 0.13.0
 */
final class ExecutionPolicyCompiler {
    static final List<String> PROTECTED_PATHS = List.of(
            ".git",
            ".cc-java",
            ".claude",
            ".mcp.json",
            "config/provider.local.properties");

    private static final Set<String> SAFE_ENVIRONMENT_NAMES = Set.of(
            "APPDATA", "CLICOLOR", "COMMONPROGRAMFILES", "COMSPEC", "CI",
            "GRADLE_USER_HOME", "HOME", "JAVA_HOME", "LANG", "LC_ALL",
            "LOCALAPPDATA", "M2_HOME", "MAVEN_HOME", "NO_COLOR",
            "NPM_CONFIG_CACHE", "PATH", "PATHEXT", "PROGRAMDATA",
            "PROGRAMFILES", "PROGRAMFILES(X86)", "SYSTEMROOT", "TEMP",
            "TERM", "TMP", "TMPDIR", "USERPROFILE", "WINDIR");

    private ExecutionPolicyCompiler() {
    }

    /**
     * 验证策略并冻结可供平台计划使用的输入。
     *
     * @param policy 不可信来源合并后的有效策略
     * @param workspace 可信 Composition Root 提供的 Workspace
     * @return 已规范化策略
     * @throws IOException 策略超出当前后端可表达子集时
     */
    static CompiledPolicy compile(ExecutionPolicy policy, Path workspace) throws IOException {
        Objects.requireNonNull(policy, "policy 不能为空");
        Path canonicalWorkspace = workspace.toRealPath();
        validateFiles(policy.file(), canonicalWorkspace);
        validateProcess(policy.process());
        validateNetwork(policy.network());
        validateEnvironment(policy.environment().variables(), policy.secret());
        return new CompiledPolicy(canonicalWorkspace, policy.environment().variables());
    }

    private static void validateFiles(FileAccessPolicy files, Path workspace) throws IOException {
        if (files.readOnlyRoots().size() != 1
                || files.writableRoots().size() != 1
                || !same(files.readOnlyRoots().getFirst(), workspace)
                || !same(files.writableRoots().getFirst(), workspace)
                || !files.deniedRoots().equals(PROTECTED_PATHS)) {
            throw new IOException("FILE_POLICY_UNSUPPORTED");
        }
    }

    private static boolean same(String identity, Path workspace) {
        try {
            return Path.of(identity).toRealPath().equals(workspace);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static void validateProcess(ProcessPolicy process) throws IOException {
        if (!process.allowDescendants()
                || process.allowDetach()
                || process.allowPrivilegeEscalation()) {
            throw new IOException("PROCESS_POLICY_UNSUPPORTED");
        }
    }

    private static void validateNetwork(NetworkPolicy network) throws IOException {
        if (!network.denyAll() || !network.allowedEndpoints().isEmpty()) {
            throw new IOException("NETWORK_POLICY_UNSUPPORTED");
        }
    }

    private static void validateEnvironment(
            Map<String, String> environment,
            SecretPolicy secrets) throws IOException {
        if (!secrets.deniedNames().equals(SecretPolicy.common().deniedNames())) {
            throw new IOException("SECRET_POLICY_UNSUPPORTED");
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!SAFE_ENVIRONMENT_NAMES.contains(entry.getKey().toUpperCase(java.util.Locale.ROOT))
                    || secrets.deniedNames().contains(entry.getKey())
                    || entry.getValue().indexOf('\0') >= 0
                    || entry.getValue().length() > 32_768) {
                throw new IOException("ENVIRONMENT_POLICY_UNSUPPORTED");
            }
        }
    }

    /** 已验证、可由当前后端完整表达的执行计划输入。 */
    record CompiledPolicy(Path workspace, Map<String, String> environment) {
        CompiledPolicy {
            workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
            environment = Map.copyOf(environment);
        }
    }
}
