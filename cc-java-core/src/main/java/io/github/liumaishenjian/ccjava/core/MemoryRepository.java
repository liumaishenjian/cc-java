package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.MemoryIndex;
import io.github.liumaishenjian.ccjava.domain.MemoryMutationResult;
import io.github.liumaishenjian.ccjava.domain.MemoryTopic;
import java.util.Optional;

/**
 * Core 访问 M1 topic 与持久 M2 Index 的框架无关 Port。
 *
 * <p>{@link #saveTopic(MemoryTopic, Optional)} 以 expected digest 区分 create/update：create
 * 必须传空 expected digest 且候选摘要为空；update 必须传读取时摘要，候选自身也必须携带同一摘要。
 * {@link #deleteTopic(String, String)} 同样要求读取时摘要。Adapter 必须在每次副作用前重新校验当前
 * 文件，不能把 Memory API 解释成 Permission、Session grant 或 OS Sandbox。</p>
 *
 * @since 0.7.0
 */
public interface MemoryRepository {

    /**
     * 安全读取一个完整 topic。
     *
     * @param name 已验证 slug
     * @return 不存在或无法安全读取时为空
     */
    Optional<MemoryTopic> loadTopic(String name);

    /**
     * 创建或更新 topic。
     *
     * @param topic 创建候选或携带读取摘要的更新候选
     * @param expectedDigest create 为空；update 为读取时摘要
     * @return 成功或结构化拒绝结果
     */
    MemoryMutationResult saveTopic(MemoryTopic topic, Optional<String> expectedDigest);

    /**
     * 按读取时摘要删除 topic。
     *
     * @param name 已验证 slug
     * @param expectedDigest 读取时摘要
     * @return 成功或结构化拒绝结果
     */
    MemoryMutationResult deleteTopic(String name, String expectedDigest);

    /**
     * 从当前安全 M3 Catalog 重建、持久化并返回 M2 Index。
     *
     * @return 有界 Index；持久化失败由实现以隐私安全方式报告
     */
    MemoryIndex loadIndex();
}
