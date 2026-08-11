package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionId;
import io.github.liumaishenjian.ccjava.domain.subagent.AgentDefinitionSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 提供一次 Session 已冻结的严格 Agent definition 快照。
 *
 * <p>查询不得重新读取磁盘；来源变化只影响下一 Session。</p>
 * @since 0.12.0
 */
public interface AgentDefinitionCatalog {
    /**
     * 从已冻结 catalog 查询定义，不重新读取磁盘。
     *
     * @param id 稳定 Agent definition identity
     * @return 匹配的 immutable snapshot
     */
    Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id);

    /**
     * 列出当前 Session 冻结的 catalog。
     *
     * @return 按稳定来源/identity 顺序排列的全部快照
     */
    List<AgentDefinitionSnapshot> snapshots();
}
