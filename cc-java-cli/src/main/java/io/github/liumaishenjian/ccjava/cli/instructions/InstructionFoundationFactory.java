package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.instructions.DeterministicInstructionDiscovery;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionDiscovery;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoadResult;
import io.github.liumaishenjian.ccjava.core.instructions.InstructionLoader;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * S08 A2 生产装配的显式接缝。
 *
 * <p>本类型只建立 Instructions 发现依赖，尚未将结果接入 Headless Runtime、Canonical
 * Transcript 或模型请求。它把 user/workspace Adapter 路由至同一个确定性 Core 服务，避免
 * 任何来源绕过已验证 Loader。</p>
 *
 * @since 0.8.0
 */
public final class InstructionFoundationFactory {

    private InstructionFoundationFactory() {
    }

    /**
     * 以 Composition Root 已解析的用户 home 和 Workspace 建立 A2 基础设施。
     *
     * @param userHome 启动环境提供的用户 home，不得来自模型或仓库文本
     * @param workspace 当前 Workspace 根
     * @return 尚未接入 Runtime 的受限基础设施
     * @throws IOException Workspace 无法规范化时
     * @throws WorkspaceAccessException Workspace 不满足既有安全边界时
     */
    public static InstructionFoundation open(Path userHome, Path workspace)
            throws IOException, WorkspaceAccessException {
        UserInstructionRootGuard userRoot = new UserInstructionRootGuard(
                Objects.requireNonNull(userHome, "userHome 不能为空"));
        WorkspaceGuard workspaceGuard = new WorkspaceGuard(
                Objects.requireNonNull(workspace, "workspace 不能为空"));
        GitIgnorePolicy gitIgnorePolicy = new GitIgnorePolicy(workspaceGuard.workspace());
        UserInstructionLoader userLoader = new UserInstructionLoader(userRoot);
        WorkspaceInstructionLoader workspaceLoader = new WorkspaceInstructionLoader(workspaceGuard, gitIgnorePolicy);
        InstructionLoader routedLoader = new RoutedInstructionLoader(userLoader, workspaceLoader);
        return new InstructionFoundation(userRoot, workspaceGuard, gitIgnorePolicy, userLoader, workspaceLoader,
                new InstructionCandidatePlanner(), new DeterministicInstructionDiscovery(routedLoader));
    }

    /**
     * 未接入既有 Headless composition 的 A2 基础设施集合。
     *
     * @param userRoot 独立用户级固定路径守卫
     * @param workspaceGuard 仅供 Workspace 内候选验证的既有守卫
     * @param gitIgnorePolicy 固定 Local 候选的 fail-closed Git 证明
     * @param userLoader 固定 USER 候选加载器
     * @param workspaceLoader Workspace 内固定候选加载器
     * @param planner 只接受已验证目标的 Workspace 候选规划器
     * @param discovery 按 ADR-047 限制与排序发现的 Core 接缝
     */
    public record InstructionFoundation(
            UserInstructionRootGuard userRoot,
            WorkspaceGuard workspaceGuard,
            GitIgnorePolicy gitIgnorePolicy,
            UserInstructionLoader userLoader,
            WorkspaceInstructionLoader workspaceLoader,
            InstructionCandidatePlanner planner,
            InstructionDiscovery discovery) {

        /** 校验所有安全边界和发现服务均已建立。 */
        public InstructionFoundation {
            userRoot = Objects.requireNonNull(userRoot, "userRoot 不能为空");
            workspaceGuard = Objects.requireNonNull(workspaceGuard, "workspaceGuard 不能为空");
            gitIgnorePolicy = Objects.requireNonNull(gitIgnorePolicy, "gitIgnorePolicy 不能为空");
            userLoader = Objects.requireNonNull(userLoader, "userLoader 不能为空");
            workspaceLoader = Objects.requireNonNull(workspaceLoader, "workspaceLoader 不能为空");
            planner = Objects.requireNonNull(planner, "planner 不能为空");
            discovery = Objects.requireNonNull(discovery, "discovery 不能为空");
        }
    }

    private record RoutedInstructionLoader(
            UserInstructionLoader userLoader, WorkspaceInstructionLoader workspaceLoader) implements InstructionLoader {
        private RoutedInstructionLoader {
            userLoader = Objects.requireNonNull(userLoader, "userLoader 不能为空");
            workspaceLoader = Objects.requireNonNull(workspaceLoader, "workspaceLoader 不能为空");
        }

        @Override
        public InstructionLoadResult load(InstructionCandidate candidate, CancellationToken cancellationToken) {
            return candidate.sourceKind() == InstructionSourceKind.USER
                    ? userLoader.load(candidate, cancellationToken)
                    : workspaceLoader.load(candidate, cancellationToken);
        }
    }
}
