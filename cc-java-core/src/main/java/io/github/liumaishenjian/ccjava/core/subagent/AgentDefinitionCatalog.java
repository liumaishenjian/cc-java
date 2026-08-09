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
    Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id);
    List<AgentDefinitionSnapshot> snapshots();
}
