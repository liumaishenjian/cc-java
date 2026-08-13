package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialResolverTest {
    @Test
    void explicitAndDefaultInvalidLayersNeverFallBackToEnvironment() {
        FakeStore store = new FakeStore(new CredentialStore.Snapshot(4, List.of(
                profile("anthropic", "broken", new SecretRef.Store("0123456789abcdef0123456789abcdef"))),
                Map.of("anthropic", "broken")));
        CredentialResolver resolver = new CredentialResolver(store,
                Map.of("CC_JAVA_ANTHROPIC_API_KEY", "must-not-be-used"));
        assertThatThrownBy(() -> resolver.resolve("anthropic", Optional.empty(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE));
        assertThat(store.readCount).isEqualTo(1);
        assertThatThrownBy(() -> resolver.resolve("anthropic", Optional.of("absent"), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure ->
                        assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN));
    }

    @Test
    void environmentEphemeralIsUsedOnlyWhenStoredLayersAreUnconfigured() {
        CredentialResolver resolver = new CredentialResolver(new FakeStore(
                new CredentialStore.Snapshot(0, List.of(), Map.of())),
                Map.of("CC_JAVA_OPENROUTER_API_KEY", "ephemeral-value"));
        try (CredentialResolver.ResolvedCredential value = resolver.resolve(
                "openrouter", Optional.empty(), CancellationToken.none())) {
            assertThat(value.profileId()).isEqualTo("env-ephemeral");
            assertThat(value.generation()).isZero();
        }
    }

    private static CredentialProfile profile(String provider, String profile, SecretRef ref) {
        return new CredentialProfile(profile, provider, ref, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }
    private static final class FakeStore implements CredentialStore {
        private final Snapshot snapshot; private int readCount;
        private FakeStore(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot snapshot(CancellationToken cancellation) { return snapshot; }
        @Override public CredentialProfile saveStore(String p,String i,SecretMaterial s,boolean d,CancellationToken c){throw new UnsupportedOperationException();}
        @Override public CredentialProfile saveEnv(String p,String i,String e,boolean d,CancellationToken c){throw new UnsupportedOperationException();}
        @Override public boolean secretExists(SecretRef.Store ref,CancellationToken cancellation){return false;}
        @Override public SecretMaterial readSecret(SecretRef.Store ref,CancellationToken cancellation){readCount++;throw new ProviderAuthException(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE,ProviderAuthException.Action.LOGIN,false);}
        @Override public void delete(String p,String i,long g,CancellationToken c){throw new UnsupportedOperationException();}
    }
}
