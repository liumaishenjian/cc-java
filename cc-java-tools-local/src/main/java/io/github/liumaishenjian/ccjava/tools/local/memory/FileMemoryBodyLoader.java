package io.github.liumaishenjian.ccjava.tools.local.memory;

import io.github.liumaishenjian.ccjava.core.MemoryBodyLoader;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 复用 {@link FileMemoryRepository} 全部 NOFOLLOW、重解析点、真实父目录、有界读取和稳定双读
 * 保护的 M5 正文加载 Adapter。
 *
 * <p>本类型不提供快捷文件读取路径；单文件缺失、损坏、链接、竞态或命中保守 Secret candidate
 * 规则时只返回空，使 M5 可以隔离该候选并继续其他 topic。诊断不会回显正文或路径。</p>
 *
 * @since 0.7.0
 */
public final class FileMemoryBodyLoader implements MemoryBodyLoader {

    private final FileMemoryRepository repository;
    private final SecretCandidatePolicy secretPolicy = new SecretCandidatePolicy();

    /**
     * 固定已验证的 memory root。
     *
     * @param memoryRoot M1 根目录
     */
    public FileMemoryBodyLoader(Path memoryRoot) {
        this.repository = new FileMemoryRepository(memoryRoot);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryTopic> load(String name) {
        return repository.loadTopic(name)
                .filter(topic -> !secretPolicy.isSecretCandidate(
                        topic.description() + "\n" + topic.body()));
    }
}
