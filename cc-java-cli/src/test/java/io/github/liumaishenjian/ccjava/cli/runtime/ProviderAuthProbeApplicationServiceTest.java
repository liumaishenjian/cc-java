package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.cli.provider.*;
import io.github.liumaishenjian.ccjava.cli.provider.probe.ProviderProbePort;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** probe 单 attempt、稳定映射、metadata-only 持久化与 secret 清理测试。 */
class ProviderAuthProbeApplicationServiceTest {
    @TempDir Path temporary;
    @Test void allOutcomesPersistOnlySafeMetadataAndMapFailuresStably() throws Exception {
        for(var outcome:ProviderProbePort.ProbeOutcome.values()) {
            Path home=Files.createDirectory(temporary.resolve(outcome.name()));
            var store=new RestrictedFileCredentialStore(home); var definitions=new ProviderDefinitionStore(home);
            var migration=new LegacyCredentialMigrationService(new LegacyProviderConfigurationReader(
                    Files.createDirectory(home.resolve("repo"))),definitions,store);
            AtomicInteger attempts=new AtomicInteger(); AtomicInteger nonZeroSecret=new AtomicInteger();
            ProviderProbePort fake=(definition,model,secret,timeout,cancel)->{
                attempts.incrementAndGet(); if(new String(secret).equals("probe-application-sentinel"))nonZeroSecret.incrementAndGet();
                return outcome;
            };
            var service=new ProviderAuthApplicationService(definitions,store,migration,Map.of("CC_TEST_KEY","probe-application-sentinel"),
                    new CredentialLeaseRegistry(),fake,Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"),ZoneOffset.UTC));
            service.login(new ProviderAuthApplicationService.LoginRequest("anthropic","personal",
                    ProviderAuthApplicationService.RefKind.ENV,"CC_TEST_KEY",true),null,CancellationToken.none());
            var request=new ProviderAuthApplicationService.ProbeRequest("anthropic","personal",
                    definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId(),Duration.ofSeconds(5));
            if(outcome==ProviderProbePort.ProbeOutcome.SUCCESS) assertThat(service.probe(request,CancellationToken.none()).outcome()).isEqualTo(outcome);
            else assertThatThrownBy(()->service.probe(request,CancellationToken.none())).isInstanceOfSatisfying(
                    ProviderAuthException.class,failure->assertThat(failure.code()).isEqualTo(code(outcome)));
            assertThat(attempts).hasValue(1); assertThat(nonZeroSecret).hasValue(1);
            String index=Files.readString(home.resolve(".cc-java/auth/profiles.v1.json"));
            assertThat(index).contains("\"code\":\""+outcome.name()+"\"")
                    .doesNotContain("probe-application-sentinel").doesNotContain("api.anthropic.com");
        }
    }
    private static ProviderAuthException.Code code(ProviderProbePort.ProbeOutcome value){return switch(value){
        case REJECTED->ProviderAuthException.Code.AUTH_PROBE_REJECTED;
        case RATE_LIMITED->ProviderAuthException.Code.AUTH_PROBE_RATE_LIMITED;
        case UNREACHABLE->ProviderAuthException.Code.AUTH_PROBE_UNREACHABLE;
        case TIMED_OUT->ProviderAuthException.Code.AUTH_PROBE_TIMED_OUT;
        case CANCELLED->ProviderAuthException.Code.AUTH_CANCELLED;
        case UNSUPPORTED->ProviderAuthException.Code.AUTH_PROBE_UNSUPPORTED;
        case SUCCESS->throw new IllegalArgumentException();};}
}