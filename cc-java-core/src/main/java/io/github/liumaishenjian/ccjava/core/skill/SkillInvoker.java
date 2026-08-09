package io.github.liumaishenjian.ccjava.core.skill;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.skill.SkillErrorCode;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationKind;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationRequest;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationResult;
import io.github.liumaishenjian.ccjava.domain.skill.SkillProjection;
import java.util.List;
import java.util.Objects;

/**
 * 准备 Skill 激活所需正文、资源和 Tool visibility。
 *
 * <p>只有所有 Port 成功返回后才提交 {@link SkillScope}；本服务不注册 Hook、不写 Session、
 * 不执行 Tool，也不缓存 Permission 决定。</p>
 *
 * @since 0.11.0
 */
public final class SkillInvoker {
    private final SkillCatalog catalog;
    private final SkillContentLoader contentLoader;
    private final SkillResourceReader resourceReader;
    private final SkillToolScopeNarrower narrower;

    public SkillInvoker(SkillCatalog catalog, SkillContentLoader contentLoader,
            SkillResourceReader resourceReader, SkillToolScopeNarrower narrower) {
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader 不能为空");
        this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader 不能为空");
        this.narrower = Objects.requireNonNull(narrower, "narrower 不能为空");
    }

    /**
     * 准备并原子提交一次 Skill 激活。
     *
     * @param request 调用意图
     * @param scope 当前 Run scope
     * @param runtimeVisibleTools 当前真实 Runtime 可见工具
     * @param cancellationToken 取消令牌
     * @return 成功 Projection 或结构化失败
     */
    public SkillInvocationResult invoke(SkillInvocationRequest request, SkillScope scope,
            List<String> runtimeVisibleTools, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(scope, "scope 不能为空");
        if (!scope.runId().equals(request.runId())) return SkillInvocationResult.failure(SkillErrorCode.NESTED_INVOCATION);
        var descriptor = catalog.find(request.skillId());
        if (descriptor.isEmpty()) return SkillInvocationResult.failure(SkillErrorCode.UNKNOWN_SKILL);
        if ((request.kind() == SkillInvocationKind.EXPLICIT && !descriptor.get().policy().allowsExplicit())
                || (request.kind() == SkillInvocationKind.MODEL && !descriptor.get().policy().allowsModel())) {
            return SkillInvocationResult.failure(SkillErrorCode.INVOCATION_NOT_ALLOWED);
        }
        SkillErrorCode begin = scope.begin(request.skillId());
        if (begin != null) return SkillInvocationResult.failure(begin);
        try {
            if (cancellationToken.isCancellationRequested()) return SkillInvocationResult.failure(SkillErrorCode.CANCELLED);
            var content = contentLoader.load(descriptor.get(), catalog.snapshot().snapshotId(), cancellationToken);
            var resources = resourceReader.read(descriptor.get(), cancellationToken);
            List<String> effective = narrower.narrow(runtimeVisibleTools, descriptor.get().allowedTools());
            SkillProjection projection = new SkillProjection(content, resources, effective);
            scope.commit(request.skillId());
            return SkillInvocationResult.success(projection);
        } catch (SkillLoadingException exception) {
            return SkillInvocationResult.failure(exception.code());
        } finally {
            scope.abort(request.skillId());
        }
    }
}
