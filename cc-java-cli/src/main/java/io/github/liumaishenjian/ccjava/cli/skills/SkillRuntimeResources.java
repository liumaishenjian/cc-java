package io.github.liumaishenjian.ccjava.cli.skills;

import io.github.liumaishenjian.ccjava.core.AgentTool;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.skill.SkillInvoker;
import io.github.liumaishenjian.ccjava.core.skill.SkillRunCoordinator;
import io.github.liumaishenjian.ccjava.core.skill.SkillToolScopeNarrower;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Headless Session 启动时冻结的 Skill catalog 与 Run 协调器。
 *
 * <p>User/Project root 只在构造时 metadata scan；正文和资源仍由同一个 repository 按调用
 * digest 重检后加载。该对象没有独立 Tool 执行入口，返回的 activate tool 必须与其他 Tool
 * 一同注册进唯一 Pipeline。</p>
 *
 * @since 0.11.0
 */
public final class SkillRuntimeResources {
    private final FileSkillRepository repository;
    private final SkillRunCoordinator coordinator;

    /** 从固定 user/project roots 建立当前 Session 的 immutable catalog。 */
    public SkillRuntimeResources(Path userRoot, Path projectRoot, List<String> runtimeToolNames) {
        this(userRoot, projectRoot, runtimeToolNames,
                io.github.liumaishenjian.ccjava.core.SessionJournal.noop());
    }

    /**
     * 从固定 roots 建立 catalog，并让激活安全事件写入当前 Session journal。
     *
     * @param journal 与 Runtime 共用的 durable journal
     */
    public SkillRuntimeResources(Path userRoot, Path projectRoot, List<String> runtimeToolNames,
            io.github.liumaishenjian.ccjava.core.SessionJournal journal) {
        this(userRoot, projectRoot, runtimeToolNames, journal, PluginSkillSet.empty());
    }

    /** 建立同时包含受信 immutable Plugin Skills 的 Session catalog。 */
    public SkillRuntimeResources(Path userRoot, Path projectRoot, List<String> runtimeToolNames,
            io.github.liumaishenjian.ccjava.core.SessionJournal journal, PluginSkillSet pluginSkills) {
        this(userRoot, projectRoot, runtimeToolNames, journal, pluginSkills,
                io.github.liumaishenjian.ccjava.core.skill.SkillHookBinder.none());
    }

    /** 建立同时消费受信 Skill Hook template catalog 的 Session catalog。 */
    public SkillRuntimeResources(Path userRoot, Path projectRoot, List<String> runtimeToolNames,
            io.github.liumaishenjian.ccjava.core.SessionJournal journal, PluginSkillSet pluginSkills,
            io.github.liumaishenjian.ccjava.core.skill.SkillHookBinder hookBinder) {
        repository = new FileSkillRepository(userRoot, projectRoot, pluginSkills);
        repository.load(CancellationToken.none());
        var catalog = repository.freezeCatalog();
        var invoker = new SkillInvoker(catalog, repository, repository, new SkillToolScopeNarrower());
        coordinator = new SkillRunCoordinator(catalog, invoker,
                List.copyOf(Objects.requireNonNull(runtimeToolNames, "runtimeToolNames 不能为空")), journal,
                Objects.requireNonNull(hookBinder, "hookBinder 不能为空"), repository);
    }

    /** @return 当前 catalog 有模型入口时的普通 Pipeline Tool */
    public List<AgentTool> activationTools() { return coordinator.activationTool().stream().toList(); }

    /** @return 当前 Session 的 Run scoped Skill 协调器 */
    public SkillRunCoordinator coordinator() { return coordinator; }

    /** 精确验证历史 Skill identity；只比较，不恢复活动 Run 状态。 */
    public io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryResult verifyRecovery(
            List<io.github.liumaishenjian.ccjava.domain.skill.SkillRecoveryRecord> records) {
        return new io.github.liumaishenjian.ccjava.core.skill.SkillRecoveryVerifier()
                .verify(repository.freezeCatalog().snapshot(), records, repository,
                        coordinator.runtimeToolNames());
    }

    /** @return metadata-only catalog snapshot */
    public io.github.liumaishenjian.ccjava.domain.skill.SkillCatalogSnapshot catalog() {
        return repository.freezeCatalog().snapshot();
    }
}
