package io.github.liumaishenjian.ccjava.domain.execution;

import java.util.List;
import java.util.Objects;

/**
 * 一次进程调用不可变的五维有效安全策略。
 *
 * <p>它是受管、宿主、用户与请求策略求交集后的结果；模型与项目内容不能修改。</p>
 *
 * @param file 文件策略
 * @param process 进程策略
 * @param network 网络策略
 * @param environment 环境策略
 * @param secret Secret 策略
 * @param requireIsolation 是否禁止 Local
 * @param provenance 从高到低的可信来源
 * @since 0.13.0
 */
public record ExecutionPolicy(
        FileAccessPolicy file,
        ProcessPolicy process,
        NetworkPolicy network,
        EnvironmentPolicy environment,
        SecretPolicy secret,
        boolean requireIsolation,
        List<PolicyProvenance> provenance) {
    /** 校验五维策略及其 provenance 后冻结有效 policy。 */
    public ExecutionPolicy {
        file = Objects.requireNonNull(file);
        process = Objects.requireNonNull(process);
        network = Objects.requireNonNull(network);
        environment = Objects.requireNonNull(environment);
        secret = Objects.requireNonNull(secret);
        provenance = List.copyOf(Objects.requireNonNull(provenance));
    }
}
