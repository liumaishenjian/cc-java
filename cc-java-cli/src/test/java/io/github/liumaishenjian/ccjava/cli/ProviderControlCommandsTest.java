package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.LegacyCredentialMigrationService;
import io.github.liumaishenjian.ccjava.cli.auth.LegacyProviderConfigurationReader;
import io.github.liumaishenjian.ccjava.cli.auth.RestrictedFileCredentialStore;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderControlCommandsTest {
    @TempDir Path temporary;

    @Test
    void everyProviderControlHelpBypassesRequiredValidationAndHasNoPersistentSideEffect() throws Exception {
        Fixture fixture = fixture("");
        PersistentCounts before = fixture.persistentCounts();
        String[][] commands = {
                {"providers", "--help"},
                {"providers", "list", "--help"},
                {"providers", "add", "--help"},
                {"providers", "remove", "--help"},
                {"auth", "--help"},
                {"auth", "login", "--help"},
                {"auth", "list", "--help"},
                {"auth", "status", "--help"},
                {"auth", "probe", "--help"},
                {"auth", "logout", "--help"},
                {"auth", "migrate-legacy", "--help"},
                {"models", "--help"},
                {"models", "list", "--help"},
                {"models", "add", "--help"},
                {"models", "remove", "--help"},
                {"models", "use", "--help"}
        };

        for (String[] command : commands) {
            Invocation invocation = fixture.execute(command);
            assertThat(invocation.exit()).as(String.join(" ", command)).isZero();
            assertThat(invocation.all()).as(String.join(" ", command))
                    .contains("Usage:")
                    .doesNotContain("Missing required");
            assertThat(fixture.persistentCounts()).as(String.join(" ", command)).isEqualTo(before);
        }
    }

    @Test
    void cliLoginListModelsAndLogoutHaveStableJsonAndNoSecretLeak() throws Exception {
        Fixture fixture=fixture("stdin-secret-sentinel\r\n");
        Invocation login=fixture.execute("auth","login","--provider","anthropic","--profile","personal",
                "--api-key-stdin","--set-default");
        assertThat(login.exit()).isZero();
        assertThat(login.all()).doesNotContain("stdin-secret-sentinel");

        Invocation list=fixture.execute("auth","list","--json");
        assertThat(list.exit()).isZero();
        var root=JsonMapper.builder().build().readTree(list.stdout());
        assertThat(root.get("version").intValue()).isEqualTo(1);
        assertThat(root.get("profiles").get(0).get("providerId").asText()).isEqualTo("anthropic");
        assertThat(list.all()).doesNotContain("stdin-secret-sentinel")
                .doesNotContain("secretId").doesNotContain("variableName");

        Invocation models=fixture.execute("models","list","--provider","anthropic","--json");
        assertThat(models.exit()).isZero();
        assertThat(models.stdout()).contains("\"version\":1").doesNotContain("api.anthropic.com");

        Invocation confirmation=fixture.execute("auth","logout","--provider","anthropic","--profile","personal");
        assertThat(confirmation.exit()).isEqualTo(2);
        Invocation logout=fixture.execute("auth","logout","--provider","anthropic","--profile","personal","--yes");
        assertThat(logout.exit()).isZero();
        assertThat(logout.stdout()).contains("was not revoked").doesNotContain("stdin-secret-sentinel");
    }

    @Test
    void modelsAddRemoveAndUseDefaultPersistAcrossServiceReopen() throws Exception {
        Fixture fixture=fixture("");
        fixture.service().login(new ProviderAuthApplicationService.LoginRequest("anthropic","personal",
                ProviderAuthApplicationService.RefKind.ENV,"CC_TEST",true),null,CancellationToken.none());

        assertThat(fixture.execute("models","add","--provider","anthropic","--model","overlay-cli").exit()).isZero();
        assertThat(fixture.execute("models","use","--provider","anthropic","--model","overlay-cli").exit()).isZero();
        ProviderAuthApplicationService reopened=service(fixture.home(),fixture.repository());
        assertThat(reopened.effectiveSelection()).get()
                .extracting(io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot::modelId)
                .isEqualTo("overlay-cli");
        assertThat(fixture.execute("models","remove","--provider","anthropic","--model","overlay-cli").exit())
                .isEqualTo(2);

        assertThat(fixture.execute("models","add","--provider","openrouter","--model","temporary-cli").exit()).isZero();
        assertThat(fixture.execute("models","remove","--provider","openrouter","--model","temporary-cli").exit()).isZero();
        assertThat(fixture.execute("models","add","--provider","team","--model","forbidden").exit()).isEqualTo(3);
    }

    @Test
    void stdinRejectsOversizeAndMalformedUtf8WithInputExitCode() throws Exception {
        byte[] oversized=new byte[16*1024+1];java.util.Arrays.fill(oversized,(byte)'x');
        Fixture large=fixture(oversized);
        Invocation largeResult=large.execute("auth","login","--provider","anthropic","--profile","p","--api-key-stdin");
        assertThat(largeResult.exit()).isEqualTo(2);
        Fixture malformed=fixture(new byte[]{(byte)0xc3,(byte)0x28});
        Invocation malformedResult=malformed.execute("auth","login","--provider","anthropic","--profile","p","--api-key-stdin");
        assertThat(malformedResult.exit()).isEqualTo(2);
    }

    @Test
    void consoleReadPasswordNullIsStableTypedFailure() {
        var input = ProviderControlCommands.consoleInput(() -> null);

        assertThatThrownBy(input::read)
                .isInstanceOfSatisfying(io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED));
    }

    @Test
    void credentialPreviewOnlyExposesBoundedAsciiFragments() {
        assertThat(ProviderControlCommands.credentialPreview("sk-protected-a9K2".toCharArray()))
                .isEqualTo("sk-...a9K2");
        assertThat(ProviderControlCommands.credentialPreview("short-key".toCharArray()))
                .isEqualTo("已保存（已隐藏）");
        assertThat(ProviderControlCommands.credentialPreview("sk-unsafe-value-换行".toCharArray()))
                .isEqualTo("已保存（已隐藏）");
    }

    @Test
    void applicationAddCompatibleProviderReusesDefinitionAndStoreValidation() throws Exception {
        Fixture fixture = fixture("");
        var added = fixture.service().addCompatibleProvider(
                new ProviderAuthApplicationService.AddProviderRequest(
                        "team", "Team Gateway", "https://gateway.example/v1", "model-x"),
                CancellationToken.none());
        assertThat(added).extracting(
                ProviderAuthApplicationService.ProviderAddedSummary::providerId,
                ProviderAuthApplicationService.ProviderAddedSummary::displayName,
                ProviderAuthApplicationService.ProviderAddedSummary::modelId)
                .containsExactly("team", "Team Gateway", "model-x");
        assertThat(service(fixture.home(), fixture.repository()).listModels(java.util.Optional.of("team"),
                CancellationToken.none())).extracting(ProviderAuthApplicationService.ModelSummary::modelId)
                .containsExactly("model-x");
        long generation = new ProviderDefinitionStore(fixture.home()).snapshot(CancellationToken.none()).generation();
        assertThatThrownBy(() -> fixture.service().addCompatibleProvider(
                new ProviderAuthApplicationService.AddProviderRequest(
                        "plain", "Plain", "http://gateway.example/v1", "model-y"), CancellationToken.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service().addCompatibleProvider(
                new ProviderAuthApplicationService.AddProviderRequest(
                        "team", "Duplicate", "https://duplicate.example/v1", "model-y"), CancellationToken.none()))
                .isInstanceOf(io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException.class);
        assertThat(new ProviderDefinitionStore(fixture.home()).snapshot(CancellationToken.none()).generation())
                .isEqualTo(generation);
    }

    @Test
    void providersJsonNeverDisplaysUriOrHeaders() throws Exception {
        Fixture fixture=fixture("");
        assertThat(fixture.execute("providers","add","--id","team","--kind","openai-compatible",
                "--base-url","https://private.example/v1","--model","model-x").exit()).isZero();
        Invocation listed=fixture.execute("providers","list","--json");
        assertThat(listed.exit()).isZero();
        assertThat(listed.stdout()).contains("team").doesNotContain("private.example").doesNotContain("X-");
    }

    private Fixture fixture(String stdin) throws Exception{return fixture(stdin.getBytes(StandardCharsets.UTF_8));}
    @Test
    void authProbeUsesServiceAndEmitsStableSafeResult() throws Exception {
        Fixture fixture=fixture(new byte[0]);
        fixture.service().login(new ProviderAuthApplicationService.LoginRequest("anthropic","personal",
                ProviderAuthApplicationService.RefKind.ENV,"CC_TEST",true),null,CancellationToken.none());
        StringWriter out=new StringWriter(),err=new StringWriter();
        int exit=CcJavaCliMain.executeProviderControl(new String[]{"auth","probe","--provider","anthropic",
                "--profile","personal"},fixture.service(),new ByteArrayInputStream(new byte[0]),
                new PrintWriter(out,true),new PrintWriter(err,true));
        assertThat(exit).isEqualTo(5); assertThat(err.toString()).contains("AUTH_PROBE_UNSUPPORTED");
        assertThat(out.toString()).doesNotContain("CC_TEST");
    }
    private Fixture fixture(byte[] stdin) throws Exception{
        Path home=Files.createDirectory(temporary.resolve("home-"+Files.list(temporary).count()));
        Path repo=Files.createDirectory(temporary.resolve("repo-"+Files.list(temporary).count()));
        return new Fixture(service(home,repo),stdin,home,repo);
    }
    private ProviderAuthApplicationService service(Path home,Path repo){
        var credentials=new RestrictedFileCredentialStore(home);var definitions=new ProviderDefinitionStore(home);
        var migration=new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(repo),definitions,credentials);
        return new ProviderAuthApplicationService(definitions,credentials,migration,Map.of(
                "CC_TEST","cli-probe-sentinel"));
    }
    private record Fixture(ProviderAuthApplicationService service,byte[] stdin,Path home,Path repository){
        Invocation execute(String...args){StringWriter out=new StringWriter(),err=new StringWriter();
            int exit=CcJavaCliMain.executeProviderControl(args,service,new ByteArrayInputStream(stdin),
                    new PrintWriter(out,true),new PrintWriter(err,true));return new Invocation(exit,out.toString(),err.toString());}
        PersistentCounts persistentCounts(){
            return new PersistentCounts(
                    service.listProviders(CancellationToken.none()).size(),
                    service.listProfiles(java.util.Optional.empty(),CancellationToken.none()).size(),
                    service.listModels(java.util.Optional.empty(),CancellationToken.none()).size());
        }
    }
    private record PersistentCounts(int providers,int credentials,int models) { }
    private record Invocation(int exit,String stdout,String stderr){String all(){return stdout+stderr;}}
}
