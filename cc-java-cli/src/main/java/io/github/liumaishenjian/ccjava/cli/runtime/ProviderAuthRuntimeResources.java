package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.CredentialLeaseRegistry;
import io.github.liumaishenjian.ccjava.cli.auth.CredentialResolver;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.provider.SelectedProviderRouteFactory;
import io.github.liumaishenjian.ccjava.cli.provider.probe.JdkProviderProbeTransport;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGatewayException;
import io.github.liumaishenjian.ccjava.core.ModelStreamObserver;
import io.github.liumaishenjian.ccjava.core.RunScopedModelGateway;
import io.github.liumaishenjian.ccjava.core.StreamingModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.model.springai.provider.ProviderGatewayFactoryRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Provider/Auth 控制面、gateway factory 与 lease registry 的用户级资源所有者。
 *
 * <p>Composition Root 只解析一次 user home，并让 CLI/stdio/TUI 共享同一应用服务。模型 Gateway
 * 延迟到每个 run 边界解析 secret；资源关闭会 fence 并关闭所有进程内 route。</p>
 */
public final class ProviderAuthRuntimeResources implements AutoCloseable {
    private final ProviderAuthApplicationService service;
    private final CredentialLeaseRegistry leases;
    private final SelectedProviderRouteFactory routes;
    private final JdkProviderProbeTransport probeTransport;

    private ProviderAuthRuntimeResources(ProviderAuthApplicationService service, CredentialLeaseRegistry leases,
                                         SelectedProviderRouteFactory routes,
                                         JdkProviderProbeTransport probeTransport) {
        this.service=Objects.requireNonNull(service); this.leases=Objects.requireNonNull(leases);
        this.routes=Objects.requireNonNull(routes); this.probeTransport=Objects.requireNonNull(probeTransport);
    }

    /**
     * 从固定 home、repository root 与环境快照装配共享服务、route factory 与 lease registry。
     *
     * @param userHome 已解析的用户主目录
     * @param repositoryRoot 用于读取 legacy Provider 配置的仓库根目录
     * @param environment 用于解析 ENV credential 的环境变量快照
     * @return 持有共享 Provider/Auth 运行时资源的可关闭对象
     */
    public static ProviderAuthRuntimeResources open(Path userHome, Path repositoryRoot,
                                                    Map<String, String> environment) {
        Path fixedHome=Objects.requireNonNull(userHome).toAbsolutePath().normalize();
        RestrictedFileCredentialStore credentials=new RestrictedFileCredentialStore(fixedHome);
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(fixedHome);
        LegacyCredentialMigrationService migration=new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repositoryRoot),definitions,credentials);
        CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        JdkProviderProbeTransport probe=new JdkProviderProbeTransport((request,cancellation) -> {
            if(cancellation.isCancellationRequested()) return NetworkAccessDecision.deny(NetworkAccessReason.CANCELLED);
            boolean fixed=request.purpose()==NetworkPurpose.PROVIDER_AUTH_PROBE
                    && !request.redirectsAllowed()
                    && "https".equals(request.scheme());
            return fixed?NetworkAccessDecision.allow():NetworkAccessDecision.deny(NetworkAccessReason.INVALID_TARGET);
        });
        ProviderAuthApplicationService service=new ProviderAuthApplicationService(
                definitions,credentials,migration,environment,leases,probe,java.time.Clock.systemUTC());
        SelectedProviderRouteFactory routes=new SelectedProviderRouteFactory(definitions,
                new CredentialResolver(credentials,environment),leases,ProviderGatewayFactoryRegistry.production());
        return new ProviderAuthRuntimeResources(service,leases,routes,probe);
    }

    /**
     * 返回所有 surface 共用的应用服务。
     *
     * @return 共享的 Provider/Auth 应用服务
     */
    public ProviderAuthApplicationService service(){return service;}

    /**
     * 返回延迟到 Run 边界冻结 selection 的唯一 Router 路径。
     *
     * <p>包装层同时取得应用服务的 active-run fence，因此 {@code /models} 与 stdio
     * {@code provider.control models.use} 在真实 Headless Run 期间确定性拒绝。两个 scope 按逆序关闭；
     * 即使底层 route 关闭失败，应用 fence 也不会泄漏。</p>
     *
     * @return 在每次 Run 开始时冻结选择并管理 fence 的模型 Gateway
     */
    public RunScopedModelGateway modelGateway() {
        return fencedGateway(routes.lazyGateway(service::effectiveSelection), service);
    }

    /** 包级测试 seam：只组合 Run fence，不替换生产 route factory。 */
    static RunScopedModelGateway fencedGateway(RunScopedModelGateway delegate,
                                               ProviderAuthApplicationService service) {
        return new FencedRunGateway(delegate, service);
    }

    private static final class FencedRunGateway implements RunScopedModelGateway, StreamingModelGateway {
        private final RunScopedModelGateway delegate;
        private final ProviderAuthApplicationService service;

        private FencedRunGateway(RunScopedModelGateway delegate, ProviderAuthApplicationService service) {
            this.delegate = Objects.requireNonNull(delegate);
            this.service = Objects.requireNonNull(service);
        }

        @Override
        public RunScope openRun() {
            ProviderAuthApplicationService.RunSelection selection = service.beginRun();
            try {
                RunScope route = delegate.openRun();
                return new RunScope() {
                    private final java.util.concurrent.atomic.AtomicBoolean closed =
                            new java.util.concurrent.atomic.AtomicBoolean();

                    @Override
                    public void bindCancellation(Runnable cancellation) {
                        route.bindCancellation(cancellation);
                    }

                    @Override
                    public void close() {
                        if (!closed.compareAndSet(false, true)) return;
                        try {
                            route.close();
                        } finally {
                            selection.close();
                        }
                    }
                };
            } catch (RuntimeException | Error failure) {
                selection.close();
                throw failure;
            }
        }

        @Override
        public ModelTurn complete(ModelRequest request, ModelStreamObserver observer,
                                  CancellationToken cancellation) throws ModelGatewayException {
            if (!(delegate instanceof StreamingModelGateway streaming)) {
                return delegate.complete(request);
            }
            return streaming.complete(request, observer, cancellation);
        }

        @Override
        public ModelTurn complete(ModelRequest request) throws ModelGatewayException {
            return delegate.complete(request);
        }
    }

    /** fence 并关闭所有进程内 route lease。 */
    @Override public void close(){leases.close(); probeTransport.close();}
}
