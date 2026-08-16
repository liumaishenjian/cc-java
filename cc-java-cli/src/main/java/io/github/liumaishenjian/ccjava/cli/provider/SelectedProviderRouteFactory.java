package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver;
import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.core.model.ModelProviderRoute;
import io.github.liumaishenjian.ccjava.core.model.ProviderRouter;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.model.CapabilitySupport;
import io.github.liumaishenjian.ccjava.domain.model.ModelCapability;
import io.github.liumaishenjian.ccjava.domain.model.ModelProviderCapabilitySnapshot;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayConfiguration;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 从 run 边界冻结的 selection、definition、profile generation 与 secret 创建单一路由。
 *
 * <p>每个 run 只创建一个 {@link ModelProviderRoute}，并始终以 {@link ProviderRouter} 作为
 * {@code ModelGateway}。Run route 绑定到可继承的调用上下文：同一 Gateway 可服务并发父/子
 * Runtime，而每个模型虚拟线程只继承所属 run 的 route。缺失 secret、鉴权失败、429 或 timeout
 * 都不会改选 profile/provider。</p>
 */
public final class SelectedProviderRouteFactory {
    private final ProviderDefinitionStore definitions;
    private final CredentialResolver resolver;
    private final CredentialLeaseRegistry leases;
    private final ProviderGatewayFactoryRegistry factories;

    /**
     * 创建无 fallback 的 selected-route factory。
     *
     * @param definitions Provider 定义快照来源
     * @param resolver 鉴权凭证解析器
     * @param leases 凭证租约注册表
     * @param factories Provider Gateway 工厂注册表
     */
    public SelectedProviderRouteFactory(ProviderDefinitionStore definitions, CredentialResolver resolver,
                                        CredentialLeaseRegistry leases, ProviderGatewayFactoryRegistry factories) {
        this.definitions=Objects.requireNonNull(definitions); this.resolver=Objects.requireNonNull(resolver);
        this.leases=Objects.requireNonNull(leases); this.factories=Objects.requireNonNull(factories);
    }

    /**
     * 创建延迟到 run 边界才解析 selection 与 secret 的 Gateway。
     *
     * @param selection 当前 Provider 选择快照来源
     * @return 按 run 隔离路由与凭证租约的延迟 Gateway
     */
    public RunScopedModelGateway lazyGateway(Supplier<Optional<ProviderSelectionSnapshot>> selection) {
        return new LazyRunGateway(Objects.requireNonNull(selection));
    }

    private OpenedRoute open(ProviderSelectionSnapshot selection, java.time.Duration runBudget) {
        ProviderDefinition definition=definitions.snapshot(CancellationToken.none()).catalog().require(selection.providerId());
        if(!definition.models().contains(selection.modelId())) throw failure(ProviderAuthException.Code.MODEL_UNKNOWN);
        CredentialResolver.ResolvedCredential credential=resolver.resolve(selection.providerId(),
                Optional.of(selection.profileId()),CancellationToken.none());
        try {
            char[] chars=credential.secret().copyChars();
            io.github.liumaishenjian.ccjava.core.ModelGateway provider;
            try {
                provider=factories.require(kind(definition)).create(new ProviderGatewayConfiguration(
                        definition.providerId(),definition.baseUri(),selection.modelId(),definition.staticHeaders(),
                        narrowerTimeout(definition.requestTimeout(), runBudget),chars));
            } finally { java.util.Arrays.fill(chars,'\0'); }
            CredentialLeaseRegistry.Lease lease;
            try {
                lease=leases.acquire(selection.providerId(),selection.profileId(),credential.generation(), () -> {
                    credential.close();
                    if (provider instanceof AutoCloseable closeable) {
                        try { closeable.close(); } catch (Exception ignored) { }
                    }
                });
            } catch (RuntimeException failure) {
                credential.close();
                if (provider instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception ignored) { }
                throw failure;
            }
            ModelProviderCapabilitySnapshot capabilities=capabilities(selection);
            ProviderRouter router=new ProviderRouter(List.of(new ModelProviderRoute(selection.providerId(),provider,capabilities)),
                    new io.github.liumaishenjian.ccjava.core.model.ProviderRoutePolicy(1,
                            java.time.Duration.ofMinutes(30),java.time.Duration.ZERO,-1,1,java.time.Clock.systemUTC()));
            return new OpenedRoute(router,lease);
        } catch(RuntimeException failure) { credential.close(); throw failure; }
    }
    private static java.time.Duration narrowerTimeout(
            java.time.Duration providerTimeout, java.time.Duration runBudget) {
        Objects.requireNonNull(runBudget, "runBudget 不能为空");
        if (runBudget.isNegative() || runBudget.isZero()) {
            throw new IllegalArgumentException("runBudget 必须大于 0");
        }
        return providerTimeout.compareTo(runBudget) <= 0 ? providerTimeout : runBudget;
    }

    private static ProviderGatewayKind kind(ProviderDefinition definition) {
        return switch(definition.kind()) {
            case OPENAI_COMPATIBLE -> ProviderGatewayKind.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProviderGatewayKind.ANTHROPIC;
            case OPENROUTER -> ProviderGatewayKind.OPENROUTER;
        };
    }
    private static ModelProviderCapabilitySnapshot capabilities(ProviderSelectionSnapshot selected) {
        EnumMap<ModelCapability,CapabilitySupport> values=new EnumMap<>(ModelCapability.class);
        values.put(ModelCapability.TEXT,CapabilitySupport.SUPPORTED);
        values.put(ModelCapability.TOOL_CALLING,CapabilitySupport.SUPPORTED);
        return ModelProviderCapabilitySnapshot.resolve(selected.providerId(),selected.modelId(),values,values);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code) {
        return new ProviderAuthException(code,ProviderAuthException.Action.SELECT_PROFILE,false);
    }
    private record OpenedRoute(StreamingModelGateway gateway,CredentialLeaseRegistry.Lease lease) {
        private void closeResource() {
            if (gateway instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception ignored) { }
            }
        }
    }

    private final class LazyRunGateway implements RunScopedModelGateway, StreamingModelGateway {
        private final Supplier<Optional<ProviderSelectionSnapshot>> selection;
        private final InheritableThreadLocal<PendingRun> current = new InheritableThreadLocal<>();
        private LazyRunGateway(Supplier<Optional<ProviderSelectionSnapshot>> selection){this.selection=selection;}
        @Override public RunScope openRun() {
            return openRun(java.time.Duration.ofMinutes(30));
        }
        @Override public RunScope openRun(java.time.Duration runBudget) {
            if (current.get() != null) {
                throw new IllegalStateException("当前调用上下文的 Run route 已打开");
            }
            PendingRun pending = new PendingRun();
            current.set(pending);
            try {
                pending.capture(selection.get().orElseThrow(
                        ()->failure(ProviderAuthException.Code.MODEL_SELECTION_AMBIGUOUS)), runBudget);
            } catch (RuntimeException failure) {
                pending.captureFailure(failure);
            } catch (Error failure) {
                current.remove();
                throw failure;
            }
            return new RunScope(){
                private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
                @Override public void bindCancellation(Runnable value){
                    pending.cancellation.set(Objects.requireNonNull(value));
                }
                @Override public void close(){if(closed.compareAndSet(false,true)){
                    if (current.get() == pending) current.remove();
                    OpenedRoute route = pending.route.get();
                    if (route != null) route.lease().close();
                }}
            };
        }
        @Override public ModelTurn complete(ModelRequest request,ModelStreamObserver observer,CancellationToken cancellation)
                throws ModelGatewayException {
            PendingRun pending=current.get();
            if(pending==null)throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,"Run route not open");
            if (pending.failure.get() != null) throw providerFailure(pending.failure.get());
            OpenedRoute route=pending.route.get();
            if(route==null)throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,"Run route not ready");
            return route.gateway().complete(request,observer,cancellation);
        }
        @Override public ModelTurn complete(ModelRequest request)throws ModelGatewayException{
            return complete(request,ignored->{},CancellationToken.none());
        }
        private final class PendingRun {
            private final AtomicReference<OpenedRoute> route = new AtomicReference<>();
            private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
            private final AtomicReference<Runnable> cancellation = new AtomicReference<>(() -> { });

            private void capture(ProviderSelectionSnapshot selection, java.time.Duration runBudget) {
                OpenedRoute opened = open(selection, runBudget);
                route.set(opened);
                opened.lease().bindCancellation(() -> cancellation.get().run());
            }

            private void captureFailure(RuntimeException value) {
                failure.set(Objects.requireNonNull(value));
            }
        }

        private ModelGatewayException providerFailure(RuntimeException failure) {
            if (failure instanceof ProviderAuthException authFailure) {
                // 这些失败发生在本地 selection/profile/secret 解析阶段，并没有收到 Provider HTTP 响应。
                // 因而不能伪造 4xx；使用无 HTTP status 的保守 Provider 配置错误摘要。
                var summary = new io.github.liumaishenjian.ccjava.domain.ModelFailureSummary(
                        io.github.liumaishenjian.ccjava.domain.ModelFailureCategory.CONFIGURATION_REQUIRED,
                        Optional.empty(), 1, false);
                return new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,
                        "Provider configuration unavailable", summary, authFailure);
            }
            return new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,
                    "Provider configuration unavailable", failure);
        }
    }
}
