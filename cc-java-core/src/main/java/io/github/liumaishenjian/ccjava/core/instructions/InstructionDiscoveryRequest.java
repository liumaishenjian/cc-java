package io.github.liumaishenjian.ccjava.core.instructions;

import io.github.liumaishenjian.ccjava.domain.instructions.InstructionCandidate;
import java.util.List;
import java.util.Objects;

/**
 * Adapter 已完成安全验证后提交给发现服务的候选集合。
 *
 * <p>Core 不从该请求推导路径或目录关系；目录激活关系必须在 Adapter 侧验证并体现为候选集合。</p>
 *
 * @param candidates 已验证的逻辑候选
 * @since 0.8.0
 */
public record InstructionDiscoveryRequest(List<InstructionCandidate> candidates) {

    /** 防御性复制候选。 */
    public InstructionDiscoveryRequest {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates 不能为空"));
    }
}
