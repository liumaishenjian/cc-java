package io.github.liumaishenjian.ccjava.cli.provider.probe;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.core.CancellationSource;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import io.github.liumaishenjian.ccjava.core.network.NetworkPurpose;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 普通测试零网络地证明专用 permission purpose、拒绝和 cancel 映射。 */
class JdkProviderProbeTransportTest {
    private static ProviderDefinition anthropic() { return new ProviderCatalog(List.of()).require("anthropic"); }

    @Test void usesDedicatedPurposeAndDoesNotSendWhenPermissionRefusesControl() {
        AtomicReference<NetworkPurpose> purpose=new AtomicReference<>();
        try(var transport=new JdkProviderProbeTransport((request,cancel)->{
            purpose.set(request.purpose());
            return NetworkAccessDecision.deny(io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason.UNSUPPORTED_CONTROL);
        },HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())) {
            assertThat(transport.probe(anthropic(),anthropic().defaultModelId(),"sentinel".toCharArray(),
                    Duration.ofSeconds(1),CancellationToken.none())).isEqualTo(ProviderProbePort.ProbeOutcome.UNREACHABLE);
        }
        assertThat(purpose.get()).isEqualTo(NetworkPurpose.PROVIDER_AUTH_PROBE);
    }

    @Test void productionConstructorDerivesOnlyOfficialHttpsOriginWithoutPublicOverride() {
        AtomicReference<String> scheme = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        AtomicReference<Boolean> redirects = new AtomicReference<>();
        try (var transport = new JdkProviderProbeTransport((request, cancel) -> {
            scheme.set(request.scheme());
            host.set(request.host());
            redirects.set(request.redirectsAllowed());
            return NetworkAccessDecision.deny(
                    io.github.liumaishenjian.ccjava.core.network.NetworkAccessReason.UNSUPPORTED_CONTROL);
        })) {
            assertThat(transport.probe(anthropic(), anthropic().defaultModelId(), "sentinel".toCharArray(),
                    Duration.ofSeconds(1), CancellationToken.none()))
                    .isEqualTo(ProviderProbePort.ProbeOutcome.UNREACHABLE);
        }
        assertThat(scheme).hasValue("https");
        assertThat(host).hasValue("api.anthropic.com");
        assertThat(redirects).hasValue(false);
        assertThat(java.util.Arrays.stream(JdkProviderProbeTransport.class.getConstructors()))
                .allSatisfy(constructor -> assertThat(java.util.Arrays.stream(constructor.getParameterTypes()))
                        .noneMatch(java.util.function.Function.class::isAssignableFrom));
        assertThat(java.util.Arrays.stream(JdkProviderProbeTransport.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("property"));
    }

    @Test void preCancelledRequestDoesNotAuthorizeOrSend() {
        CancellationSource source=new CancellationSource(); source.cancel();
        AtomicReference<NetworkPurpose> purpose=new AtomicReference<>();
        try(var transport=new JdkProviderProbeTransport((request,cancel)->{
            purpose.set(request.purpose()); return NetworkAccessDecision.allow();
        })) {
            assertThat(transport.probe(anthropic(),anthropic().defaultModelId(),"sentinel".toCharArray(),
                    Duration.ofSeconds(1),source.token())).isEqualTo(ProviderProbePort.ProbeOutcome.CANCELLED);
        }
        assertThat(purpose).hasValue(null);
    }
}