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
 * {@code ModelGateway}。缺失 secret、鉴权失败、429 或 timeout 都不会改选 profile/provider。</p>
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

    private OpenedRoute open(ProviderSelectionSnapshot selection) {
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
                        definition.requestTimeout(),chars));
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
        private final ThreadLocal<OpenedRoute> current=new ThreadLocal<>();
        private LazyRunGateway(Supplier<Optional<ProviderSelectionSnapshot>> selection){this.selection=selection;}
        @Override public RunScope openRun() {
            if(current.get()!=null) throw new IllegalStateException("Run route 已打开");
            ProviderSelectionSnapshot captured=selection.get().orElseThrow(
                    ()->failure(ProviderAuthException.Code.MODEL_SELECTION_AMBIGUOUS));
            OpenedRoute route=open(captured); current.set(route);
            return new RunScope(){
                private final AtomicReference<Runnable> cancellation=new AtomicReference<>(()->{});
                private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
                { route.lease().bindCancellation(() -> {
                    cancellation.get().run();
                }); }
                @Override public void bindCancellation(Runnable value){cancellation.set(Objects.requireNonNull(value));}
                @Override public void close(){if(closed.compareAndSet(false,true)){
                    current.remove(); route.lease().close();
                }}
            };
        }
        @Override public ModelTurn complete(ModelRequest request,ModelStreamObserver observer,CancellationToken cancellation)
                throws ModelGatewayException {
            OpenedRoute route=current.get();
            if(route==null)throw new ModelGatewayException(ModelGatewayException.FailureKind.PERMANENT,"Run route not open");
            return route.gateway().complete(request,observer,cancellation);
        }
        @Override public ModelTurn complete(ModelRequest request)throws ModelGatewayException{
            return complete(request,ignored->{},CancellationToken.none());
        }
    }
}
