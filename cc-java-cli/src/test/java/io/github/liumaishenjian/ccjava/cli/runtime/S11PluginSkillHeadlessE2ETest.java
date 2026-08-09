package io.github.liumaishenjian.ccjava.cli.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.cli.plugins.PluginPackageLoader;
import io.github.liumaishenjian.ccjava.cli.session.SessionOpenRequest;
import io.github.liumaishenjian.ccjava.core.AgentEventSink;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.ApprovalResponse;
import io.github.liumaishenjian.ccjava.domain.AssistantMessage;
import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.ModelTurnMetadata;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.SkillContextMessage;
import io.github.liumaishenjian.ccjava.domain.StopReason;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolResultMessage;
import io.github.liumaishenjian.ccjava.domain.skill.ExplicitSkillInvocation;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.mcp.McpCallOutcome;
import io.github.liumaishenjian.ccjava.mcp.McpPluginConfigDigest;
import io.github.liumaishenjian.ccjava.mcp.McpRemoteClient;
import io.github.liumaishenjian.ccjava.mcp.McpServerConfig;
import io.github.liumaishenjian.ccjava.mcp.McpToolDescriptor;
import io.github.liumaishenjian.ccjava.mcp.McpTransportConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 普通 Headless production composition 的 Plugin Skill/Tool/Hook 闭环。 */
class S11PluginSkillHeadlessE2ETest {
    private static final String SKILL = "plugin__runtime__skills__review";
    private static final String TOOL = "plugin__runtime__tool-provider__remote__search";

    @Test
    void trustedPluginSkillLoadsIntoHeadlessAndActivatesExplicitlyAndByModel(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, true);
        CopyOnWriteArrayList<ModelRequest> explicitRequests = new CopyOnWriteArrayList<>();
        ModelGateway explicit = request -> {
            explicitRequests.add(request);
            if (request.messages().stream().anyMatch(SkillContextMessage.class::isInstance)
                    && request.messages().stream().noneMatch(ToolResultMessage.class::isInstance)) {
                return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall(
                        "call-plugin", TOOL, JsonObject.empty()))), ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        };
        try (HeadlessRuntimeSession runtime = runtime(fixture, explicit, ApprovalResponse.allowOnce())) {
            runtime.open();
            assertThat(runtime.skillCatalog()).extracting(value -> value.id().value()).contains(SKILL);
            assertThat(runtime.runSkill(new ExplicitSkillInvocation(new SkillId(SKILL), "ARG_SENTINEL"))
                    .stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        assertThat(explicitRequests).anySatisfy(request -> assertThat(request.messages())
                .filteredOn(SkillContextMessage.class::isInstance).singleElement().satisfies(message -> {
                    SkillContextMessage context = (SkillContextMessage) message;
                    assertThat(context.markdown()).contains("PLUGIN_BODY_SENTINEL", "PLUGIN_RESOURCE_SENTINEL");
                    assertThat(context.arguments()).isEqualTo("ARG_SENTINEL");
                    assertThat(request.toolDefinitions()).extracting(value -> value.name()).contains(TOOL);
                }));
        assertThat(fixture.remoteCalls()).hasValue(1);
        assertThat(fixture.callNames()).containsExactly("search");

        Fixture modelFixture = fixture(root.resolve("model"), true);
        CopyOnWriteArrayList<ModelRequest> modelRequests = new CopyOnWriteArrayList<>();
        ModelGateway model = request -> {
            modelRequests.add(request);
            if (modelRequests.size() == 1) {
                return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall("activate", "activate_skill",
                        new JsonObject(Map.of("name", SKILL, "arguments", "model-arg"))))), ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        };
        try (HeadlessRuntimeSession runtime = runtime(modelFixture, model, ApprovalResponse.allowOnce())) {
            runtime.open();
            assertThat(runtime.run("activate plugin skill").stopReason()).isEqualTo(StopReason.COMPLETED);
        }
        assertThat(modelRequests).anySatisfy(request -> assertThat(request.messages())
                .filteredOn(SkillContextMessage.class::isInstance).singleElement());
    }

    @Test
    void hookLeaseAppearsOnlyAfterActivationAndClosesAtTerminal(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, true);
        CountDownLatch activated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ModelRequest> projected = new AtomicReference<>();
        ModelGateway model = request -> {
            if (request.messages().stream().anyMatch(SkillContextMessage.class::isInstance)) {
                projected.set(request);
                activated.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return ModelTurn.text("done");
        };
        try (HeadlessRuntimeSession runtime = runtime(fixture, model, ApprovalResponse.allowOnce())) {
            runtime.open();
            assertThat(runtime.hookCoordinator().runBindingCount(new io.github.liumaishenjian.ccjava.domain.RunId(
                    "run-never-active"))).isZero();
            AtomicReference<io.github.liumaishenjian.ccjava.domain.AgentRunResult> result = new AtomicReference<>();
            Thread run = Thread.ofPlatform().start(() -> result.set(runtime.runSkill(
                    new ExplicitSkillInvocation(new SkillId(SKILL), ""))));
            assertThat(activated.await(5, TimeUnit.SECONDS)).isTrue();
            io.github.liumaishenjian.ccjava.domain.RunId runId = projected.get().runId();
            assertThat(runtime.hookCoordinator().runBindingCount(runId)).isEqualTo(1);
            release.countDown();
            run.join(Duration.ofSeconds(5));
            assertThat(result.get().stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(runtime.hookCoordinator().runBindingCount(runId)).isZero();
        }
    }

    @Test
    void denyPreventsRemoteCallAndCurrentSnapshotDoesNotDriftButNewSessionDistrustsMutation(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, true);
        CopyOnWriteArrayList<ModelRequest> requests = new CopyOnWriteArrayList<>();
        ModelGateway model = request -> {
            requests.add(request);
            if (request.messages().stream().anyMatch(SkillContextMessage.class::isInstance)
                    && request.messages().stream().noneMatch(ToolResultMessage.class::isInstance)) {
                return new ModelTurn(AssistantMessage.tools(List.of(new ToolCall("denied-call", TOOL,
                        JsonObject.empty()))), ModelTurnMetadata.unknown());
            }
            return ModelTurn.text("done");
        };
        HeadlessRuntimeSession current = runtime(fixture, model, ApprovalResponse.deny());
        current.open();
        Files.writeString(fixture.skillFile(), "MUTATED", StandardCharsets.UTF_8);
        assertThat(current.runSkill(new ExplicitSkillInvocation(new SkillId(SKILL), ""))
                .stopReason()).isEqualTo(StopReason.COMPLETED);
        current.close();
        assertThat(fixture.remoteCalls()).hasValue(0);
        assertThat(requests).anySatisfy(request -> assertThat(request.messages())
                .filteredOn(ToolResultMessage.class::isInstance).singleElement().satisfies(message ->
                        assertThat(((ToolResultMessage) message).result().callId()).isEqualTo("denied-call")));

        try (HeadlessRuntimeSession changed = runtime(fixture, request -> ModelTurn.text("done"),
                ApprovalResponse.allowOnce())) {
            changed.open();
            assertThat(changed.skillCatalog()).extracting(value -> value.id().value()).doesNotContain(SKILL);
        }
    }

    private static HeadlessRuntimeSession runtime(Fixture fixture, ModelGateway gateway,
            ApprovalResponse approval) {
        HeadlessRuntimeOptions options = new HeadlessRuntimeOptions(fixture.workspace(), "fake-model",
                Duration.ofSeconds(10), PermissionMode.DEFAULT, List.of(), SessionOpenRequest.create(),
                fixture.sessions());
        return new HeadlessRuntimeSession(gateway, AgentEventSink.noop(), options,
                (invocation, definition, outcome) -> approval, ContextPreparationService.noop(), null,
                HeadlessRuntimeSession.HeadlessMemoryLayout.disabled(),
                HeadlessRuntimeSession.HeadlessInstructionLayout.production(() -> fixture.home()),
                null, true, ignored -> fixture.client());
    }

    private static Fixture fixture(Path root, boolean trusted) throws Exception {
        Files.createDirectories(root);
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path home = Files.createDirectory(root.resolve("home"));
        Path cc = Files.createDirectories(home.resolve(".cc-java"));
        Path executable = root.resolve("fake.exe").toAbsolutePath();
        McpServerConfig config = new McpServerConfig("primary",
                new McpTransportConfig.Stdio(executable, List.of(), List.of()), List.of(), List.of(),
                Duration.ofSeconds(1), true);
        Files.write(cc.resolve("extensions.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "version", 1,
                "mcpServers", List.of(Map.of("name", "primary", "transport", "stdio",
                        "command", executable.toString(), "timeoutMs", 1000)))));
        String configDigest = McpPluginConfigDigest.compute(List.of(config));
        Path source = Files.createDirectory(root.resolve("source"));
        Files.createDirectories(source.resolve("skills/review"));
        Files.createDirectories(source.resolve("hooks"));
        Files.createDirectories(source.resolve("mcp"));
        Files.createDirectories(source.resolve("providers"));
        String skill = "---\nname: review\ndescription: review plugin\ninvocation: both\nallowed-tools:\n  - "
                + TOOL + "\nresources:\n  - template.txt\nhooks:\n  - audit\n---\nPLUGIN_BODY_SENTINEL\n";
        Files.writeString(source.resolve("skills/review/SKILL.md"), skill, StandardCharsets.UTF_8);
        Files.writeString(source.resolve("skills/review/template.txt"), "PLUGIN_RESOURCE_SENTINEL",
                StandardCharsets.UTF_8);
        Files.writeString(source.resolve("hooks/audit.json"),
                "{\"version\":1,\"id\":\"audit\",\"event\":\"POST_TOOL\",\"failurePolicy\":\"FAIL_OPEN\",\"timeoutMs\":1000,\"url\":\"http://127.0.0.1:9/hook\"}");
        Files.writeString(source.resolve("mcp/primary.json"), "{}");
        Files.writeString(source.resolve("providers/remote.json"), "{}");
        String manifest = "{\"schemaVersion\":1,\"id\":\"runtime\",\"version\":\"1\",\"components\":{"
                + "\"skills\":[{\"name\":\"review\",\"path\":\"skills/review/SKILL.md\"}],"
                + "\"hooks\":[{\"name\":\"audit\",\"path\":\"hooks/audit.json\"}],"
                + "\"mcpServers\":[{\"name\":\"primary\",\"path\":\"mcp/primary.json\"}],"
                + "\"toolProviders\":[{\"name\":\"remote\",\"path\":\"providers/remote.json\","
                + "\"type\":\"mcp-backed\",\"mcpServers\":[\"primary\"],\"configDigest\":\""
                + configDigest + "\"}]}}";
        Files.writeString(source.resolve("plugin.json"), manifest, StandardCharsets.UTF_8);
        Path store = Files.createDirectories(cc.resolve("plugins"));
        var snapshot = new PluginPackageLoader().load(source);
        Path installed = Files.createDirectory(store.resolve(
                "runtime-" + snapshot.safeContentId().substring(0, 16)));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) continue;
                Path target = installed.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectory(target);
                else Files.copy(path, target);
            }
        }
        Files.writeString(store.resolve("registry.v1"), snapshot.manifest().id().value() + "\t"
                + snapshot.manifest().version() + "\t" + snapshot.fingerprint().treeDigest() + "\n",
                StandardCharsets.UTF_8);
        if (trusted) {
            Files.writeString(store.resolve("plugin-trust.v1"), snapshot.manifest().id().value() + "\t"
                    + snapshot.manifest().version() + "\t" + snapshot.fingerprint().treeDigest() + "\t"
                    + snapshot.fingerprint().manifestDigest() + "\n", StandardCharsets.UTF_8);
        }
        Path installedSkill = installed.resolve("skills/review/SKILL.md");
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> callNames = new CopyOnWriteArrayList<>();
        McpRemoteClient client = new McpRemoteClient() {
            @Override public void initialize() { }
            @Override public List<McpToolDescriptor> listTools() {
                return List.of(new McpToolDescriptor("search", "search", Map.of("type", "object")));
            }
            @Override public McpCallOutcome callTool(String name, Map<String, Object> arguments) {
                calls.incrementAndGet(); callNames.add(name); return new McpCallOutcome(false, "remote-ok");
            }
            @Override public void close() { }
        };
        return new Fixture(workspace, home, root.resolve("sessions"), installedSkill, client, calls, callNames);
    }

    private record Fixture(Path workspace, Path home, Path sessions, Path skillFile, McpRemoteClient client,
            AtomicInteger remoteCalls, CopyOnWriteArrayList<String> callNames) { }
}
