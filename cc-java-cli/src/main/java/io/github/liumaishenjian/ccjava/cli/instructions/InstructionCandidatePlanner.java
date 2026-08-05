package io.github.liumaishenjian.ccjava.cli.instructions;

import io.github.liumaishenjian.ccjava.core.instructions.DeterministicInstructionDiscovery;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionActivation;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionScopeKind;
import io.github.liumaishenjian.ccjava.domain.instructions.InstructionSourceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把 Adapter 已验证目标投影为稳定的 Workspace 指令候选。
 *
 * <p>规划器不读取文件、不接受原始路径，也不判断目标存在性；这些安全责任必须先由
 * {@link VerifiedInstructionTarget} 的工厂交给 {@code WorkspaceGuard}。多个目标按输入顺序
 * 合并其从 Workspace 根到目标目录的祖先序列，并保留首次出现的目录。</p>
 *
 * @since 0.8.0
 */
public final class InstructionCandidatePlanner {
    private static final String ROOT_AGENTS = "AGENTS.md";
    private static final String LOCAL_AGENTS = ".cc-java/AGENTS.local.md";

    /** 只创建无状态规划器；所有输入在 {@link #plan(List)} 时验证。 */
    public InstructionCandidatePlanner() {
    }

    /**
     * 生成 USER、Project、Directory 和 Local 候选。
     *
     * <p>候选固定按 USER → PROJECT → DIRECTORY（由根到目标）→ LOCAL 的低到高优先级输出，
     * 根项目只出现一次。</p>
     *
     * @param targets 已通过 Workspace Adapter 验证的目标；可为空
     * @return 低到高优先级的确定性候选
     */
    public List<InstructionCandidate> plan(List<VerifiedInstructionTarget> targets) {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets 不能为空"));
        Set<String> directories = new TreeSet<>(Comparator
                .comparingInt(InstructionCandidatePlanner::depth)
                .thenComparing(Comparator.naturalOrder()));
        for (VerifiedInstructionTarget target : targets) {
            VerifiedInstructionTarget verified = Objects.requireNonNull(target, "target 不能为空");
            directories.addAll(ancestors(startDirectory(verified)));
        }
        List<InstructionCandidate> candidates = new ArrayList<>();
        candidates.add(new InstructionCandidate(InstructionSourceKind.USER, InstructionScopeKind.USER_GLOBAL,
                "user-instructions", candidates.size(), InstructionActivation.STARTUP));
        candidates.add(new InstructionCandidate(InstructionSourceKind.PROJECT, InstructionScopeKind.WORKSPACE,
                ROOT_AGENTS, candidates.size(), InstructionActivation.STARTUP));
        for (String directory : directories) {
            if (!directory.isEmpty()) {
                candidates.add(new InstructionCandidate(InstructionSourceKind.DIRECTORY,
                        InstructionScopeKind.DIRECTORY_SUBTREE, directory + "/AGENTS.md", candidates.size(),
                        InstructionActivation.VERIFIED_TARGET));
            }
        }
        candidates.add(new InstructionCandidate(InstructionSourceKind.LOCAL, InstructionScopeKind.WORKSPACE,
                LOCAL_AGENTS, candidates.size(), InstructionActivation.STARTUP));
        return List.copyOf(candidates);
    }

    private static String startDirectory(VerifiedInstructionTarget target) {
        String path = target.protocolPath();
        if (path.equals(".")) {
            return "";
        }
        if (target.kind() == VerifiedInstructionTarget.Kind.DIRECTORY) {
            return path;
        }
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private static List<String> ancestors(String directory) {
        if (directory.isEmpty()) {
            return List.of("");
        }
        String[] components = directory.split("/");
        if (components.length > DeterministicInstructionDiscovery.MAX_DIRECTORY_DEPTH) {
            throw new IllegalArgumentException("已验证目标超过目录层级上限");
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String component : components) {
            if (component.isBlank() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("已验证目标协议路径非法");
            }
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(component);
            result.add(current.toString());
        }
        return result;
    }

    private static int depth(String directory) {
        return directory.isEmpty() ? 0 : directory.split("/").length;
    }
}
