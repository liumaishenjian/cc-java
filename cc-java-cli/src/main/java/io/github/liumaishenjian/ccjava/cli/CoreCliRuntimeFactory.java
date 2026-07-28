package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.core.AgentRuntime;
import io.github.liumaishenjian.ccjava.core.DefaultContextAssembler;
import io.github.liumaishenjian.ccjava.core.InMemorySessionStore;
import io.github.liumaishenjian.ccjava.core.LifecycleDispatcher;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.core.ToolExecutionPipeline;
import io.github.liumaishenjian.ccjava.core.ToolRegistry;
import io.github.liumaishenjian.ccjava.core.UuidAgentIdGenerator;
import io.github.liumaishenjian.ccjava.domain.AgentLimits;
import io.github.liumaishenjian.ccjava.domain.PermissionDecision;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import io.github.liumaishenjian.ccjava.domain.SessionSpec;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 装配 S02 CLI 所需的显式 Core Runtime 与单个内存 Session。
 *
 * <p>S02 尚未引入本地文件 Tool，因此 Registry 为空，Permission 与 Approval
 * 端口采用确定性拒绝默认值。真实 Provider 仅通过 {@link ModelGatewayFactory}
 * 注入；Spring AI 不能替代这里拥有的 Agent Loop。</p>
 *
 * @since 0.1.0
 */
public final class CoreCliRuntimeFactory implements CliRuntimeFactory {

    private static final int DEFAULT_MAX_MODEL_TURNS = 16;

    private final ModelGatewayFactory modelGatewayFactory;
    private final Clock clock;

    /**
     * 使用 UTC 系统时钟创建生产工厂。
     *
     * @param modelGatewayFactory 真实 Provider 装配边界
     */
    public CoreCliRuntimeFactory(ModelGatewayFactory modelGatewayFactory) {
        this(modelGatewayFactory, Clock.systemUTC());
    }

    /**
     * 使用指定时钟创建可确定性测试的工厂。
     *
     * @param modelGatewayFactory Provider 装配边界
     * @param clock Lifecycle 与 Run Deadline 的时间来源
     */
    public CoreCliRuntimeFactory(
            ModelGatewayFactory modelGatewayFactory,
            Clock clock) {
        this.modelGatewayFactory = Objects.requireNonNull(
                modelGatewayFactory,
                "modelGatewayFactory 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public CliRuntime create(
            CliConfiguration configuration,
            CliEnvironment environment,
            CliEventListener listener) throws CliStartupException {
        Objects.requireNonNull(configuration, "configuration 不能为空");
        Objects.requireNonNull(environment, "environment 不能为空");
        Objects.requireNonNull(listener, "listener 不能为空");

        ModelGateway gateway = modelGatewayFactory.create(configuration, environment);
        try {
            LifecycleDispatcher lifecycle =
                    new LifecycleDispatcher(clock, listener::onAgentEvent);
            UuidAgentIdGenerator idGenerator = new UuidAgentIdGenerator();
            InMemorySessionStore sessionStore =
                    new InMemorySessionStore(idGenerator, lifecycle);
            ToolRegistry registry = ToolRegistry.empty();
            ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                    registry,
                    (invocation, definition) -> PermissionDecision.DENY,
                    (invocation, definition) -> PermissionDecision.DENY,
                    lifecycle);
            AgentRuntime runtime = new AgentRuntime(
                    sessionStore,
                    idGenerator,
                    gateway,
                    new DefaultContextAssembler(),
                    registry,
                    pipeline,
                    lifecycle,
                    clock);
            SessionId sessionId = sessionStore.create(new SessionSpec(
                    configuration.systemInstructions(),
                    Map.of(
                            "ollamaBaseUrl",
                                    configuration.ollamaBaseUrl().value().toString(),
                            "model", configuration.model().value(),
                            "provider", configuration.providerId(),
                            "workspace", configuration.workspace().value().toString())))
                    .id();
            AgentLimits limits = new AgentLimits(
                    DEFAULT_MAX_MODEL_TURNS,
                    0,
                    configuration.timeout().value(),
                    configuration.maxRetries().value());
            return new CoreCliRuntime(
                    runtime,
                    sessionStore,
                    sessionId,
                    limits,
                    gateway);
        } catch (RuntimeException exception) {
            closeQuietly(gateway);
            throw new CliStartupException(
                    CliExitCode.INTERNAL_ERROR,
                    "Core Runtime 装配失败");
        }
    }

    private static void closeQuietly(Object resource) {
        if (resource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 启动已经失败；清理异常不能泄漏 Provider 细节或 Secret。
            }
        }
    }
}
