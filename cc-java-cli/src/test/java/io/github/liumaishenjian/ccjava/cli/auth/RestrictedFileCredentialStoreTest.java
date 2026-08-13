package io.github.liumaishenjian.ccjava.cli.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class RestrictedFileCredentialStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporary;

    @Test
    void savesReplacesReadsAndDeletesStoreProfileWithoutLeakingSecret() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        char[] source = "sentinel-provider-secret".toCharArray();
        SecretMaterial material = new SecretMaterial(source);
        Arrays.fill(source, '\0');

        CredentialProfile first = store.saveStore("anthropic", "personal", material, true, CancellationToken.none());
        CredentialStore.Snapshot saved = store.snapshot(CancellationToken.none());

        assertThat(saved.generation()).isEqualTo(1);
        assertThat(saved.providerDefaults()).containsEntry("anthropic", "personal");
        assertThat(saved.find("anthropic", "personal")).contains(first);
        try (SecretMaterial reread = store.readSecret((SecretRef.Store) first.secretRef(), CancellationToken.none())) {
            char[] copy = reread.copyChars();
            try { assertThat(copy).containsExactly("sentinel-provider-secret".toCharArray()); }
            finally { Arrays.fill(copy, '\0'); }
        }
        assertThat(material.toString()).isEqualTo("<redacted>");
        assertThatThrownBy(material::copyChars).isInstanceOf(IllegalStateException.class);

        CredentialProfile replacement = store.saveEnv("anthropic", "personal", "CC_TEST_KEY", false,
                CancellationToken.none());
        assertThat(replacement.createdAt()).isEqualTo(first.createdAt());
        assertThat(replacement.secretRef()).isInstanceOf(SecretRef.Env.class);
        assertThat(Files.list(home.resolve(".cc-java/auth/secrets"))).isEmpty();

        long generation = store.snapshot(CancellationToken.none()).generation();
        store.delete("anthropic", "personal", generation, CancellationToken.none());
        assertThat(store.snapshot(CancellationToken.none()).generation()).isEqualTo(generation + 1);
        assertThat(store.snapshot(CancellationToken.none()).profiles()).isEmpty();
    }

    @Test
    void rejectsGenerationConflictAndInvalidDefaultOrDuplicateJson() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        store.saveEnv("openrouter", "work", "CC_TEST_KEY", true, CancellationToken.none());
        assertThatThrownBy(() -> store.delete("openrouter", "work", 0, CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_TRANSACTION_CONFLICT));

        Path index = home.resolve(".cc-java/auth/profiles.v1.json");
        writeExisting(index, """
                {"schemaVersion":1,"generation":1,"providerDefaults":{"openrouter":"absent"},"profiles":[]}
                """);
        assertCorrupt(store);

        writeExisting(index, """
                {"schemaVersion":1,"schemaVersion":1,"generation":1,"providerDefaults":{},"profiles":[]}
                """);
        assertCorrupt(store);
    }

    @Test
    void missingReferencedSecretFailsWithoutFallback() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        CredentialProfile profile = store.saveStore("anthropic", "personal",
                new SecretMaterial("missing-later".toCharArray()), true, CancellationToken.none());
        Files.delete(home.resolve(".cc-java/auth/secrets/" + ((SecretRef.Store) profile.secretRef()).secretId() + ".json"));

        assertThatThrownBy(() -> store.readSecret((SecretRef.Store) profile.secretRef(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE));
        CredentialResolver resolver = new CredentialResolver(store,
                java.util.Map.of("CC_JAVA_ANTHROPIC_API_KEY", "must-not-fallback"));
        assertThatThrownBy(() -> resolver.resolve("anthropic", Optional.empty(), CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE));
    }

    @Test
    void recoversEveryDurableSaveCrashPointAndLeavesNoJournalOrOrphan() throws Exception {
        for (RestrictedFileCredentialStore.CrashPoint point : RestrictedFileCredentialStore.CrashPoint.values()) {
            Path home = Files.createDirectory(temporary.resolve("home-" + point));
            AtomicBoolean crashed = new AtomicBoolean();
            RestrictedFileCredentialStore crashing = store(home, observed -> {
                if (observed == point && crashed.compareAndSet(false, true)) throw new SimulatedCrash();
            });
            assertThatThrownBy(() -> crashing.saveStore("anthropic", "personal",
                    new SecretMaterial(("secret-" + point).toCharArray()), true, CancellationToken.none()))
                    .isInstanceOf(SimulatedCrash.class);

            CredentialStore.Snapshot recovered = store(home, ignored -> { }).snapshot(CancellationToken.none());
            boolean published = point.ordinal() >= RestrictedFileCredentialStore.CrashPoint.INDEX_PUBLISHED.ordinal();
            assertThat(recovered.find("anthropic", "personal").isPresent()).isEqualTo(published);
            assertThat(Files.exists(home.resolve(".cc-java/auth/.txn.v1.json"))).isFalse();
            long secretCount;
            try (var files = Files.list(home.resolve(".cc-java/auth/secrets"))) { secretCount = files.count(); }
            assertThat(secretCount).isEqualTo(published ? 1 : 0);
        }
    }

    @Test
    void recoversLogoutAfterIndexPublicationAndNeverRestoresProfile() throws Exception {
        for (RestrictedFileCredentialStore.CrashPoint point : RestrictedFileCredentialStore.CrashPoint.values()) {
            if (point == RestrictedFileCredentialStore.CrashPoint.NEW_SECRET_DURABLE) continue;
            Path home = Files.createDirectory(temporary.resolve("logout-" + point));
            RestrictedFileCredentialStore initial = store(home, ignored -> { });
            initial.saveStore("anthropic", "personal", new SecretMaterial("logout-secret".toCharArray()),
                    true, CancellationToken.none());
            long generation = initial.snapshot(CancellationToken.none()).generation();
            AtomicBoolean crashed = new AtomicBoolean();
            RestrictedFileCredentialStore crashing = store(home, observed -> {
                if (observed == point && crashed.compareAndSet(false, true)) throw new SimulatedCrash();
            });
            assertThatThrownBy(() -> crashing.delete("anthropic", "personal", generation, CancellationToken.none()))
                    .isInstanceOf(SimulatedCrash.class);
            CredentialStore.Snapshot recovered = store(home, ignored -> { }).snapshot(CancellationToken.none());
            boolean published = point.ordinal() >= RestrictedFileCredentialStore.CrashPoint.INDEX_PUBLISHED.ordinal();
            assertThat(recovered.find("anthropic", "personal").isEmpty()).isEqualTo(published);
            long secretCount;
            try (var files = Files.list(home.resolve(".cc-java/auth/secrets"))) { secretCount = files.count(); }
            assertThat(secretCount).isEqualTo(published ? 0 : 1);
        }
    }

    @Test
    void cancellationIsObservedWhileWaitingForProcessLock() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        store.snapshot(CancellationToken.none());
        Path lock = home.resolve(".cc-java/auth/.lock");
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Thread.ofPlatform().start(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignoredInterrupt) { Thread.currentThread().interrupt(); }
                cancelled.set(true);
            });
            CancellationToken token = token(cancelled);
            assertThatThrownBy(() -> store.snapshot(token))
                    .isInstanceOfSatisfying(ProviderAuthException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_CANCELLED));
        }
    }

    @Test
    void nonAtomicMoveSeamFailsClosedAndDoesNotPublishIndex() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileSecurity security = new RestrictedFileSecurity(home);
        RestrictedFileCredentialStore store = new RestrictedFileCredentialStore(security, CLOCK, deterministicRandom(),
                (source, target) -> { throw new java.nio.file.AtomicMoveNotSupportedException("", "", ""); },
                RestrictedFileCredentialStore.FaultInjector.none());
        assertThatThrownBy(() -> store.saveEnv("anthropic", "personal", "CC_TEST_KEY", true,
                CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
        assertThat(Files.exists(home.resolve(".cc-java/auth/profiles.v1.json"))).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsExistingWeakModeSymlinkAndHardLink() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        store.snapshot(CancellationToken.none());
        Path auth = home.resolve(".cc-java/auth");
        Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertInsecure(store);

        Files.setPosixFilePermissions(auth, PosixFilePermissions.fromString("rwx------"));
        Path index = auth.resolve("profiles.v1.json");
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(index, outside);
        assertInsecure(store);
        Files.delete(index);

        writeFresh(index, "{\"schemaVersion\":1,\"generation\":0,\"providerDefaults\":{},\"profiles\":[]}");
        Files.createLink(temporary.resolve("hard-link.json"), index);
        assertInsecure(store);
    }

    @Test
    void errorAndObjectSurfacesDoNotContainSentinelOrAbsolutePath() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore store = store(home, ignored -> { });
        String sentinel = "secret-sentinel-9031";
        CredentialProfile profile = store.saveStore("anthropic", "personal",
                new SecretMaterial(sentinel.toCharArray()), true, CancellationToken.none());
        String surfaces = profile + "\n" + profile.secretRef() + "\n" + store.snapshot(CancellationToken.none());
        assertThat(surfaces).doesNotContain(sentinel).doesNotContain(home.toAbsolutePath().toString());
        Files.delete(home.resolve(".cc-java/auth/secrets/" + ((SecretRef.Store) profile.secretRef()).secretId() + ".json"));
        try {
            store.readSecret((SecretRef.Store) profile.secretRef(), CancellationToken.none());
        } catch (ProviderAuthException failure) {
            assertThat(failure.toString()).doesNotContain(sentinel).doesNotContain(home.toString());
        }
    }

    private RestrictedFileCredentialStore store(Path home, RestrictedFileCredentialStore.FaultInjector faults) {
        return new RestrictedFileCredentialStore(new RestrictedFileSecurity(home), CLOCK, deterministicRandom(),
                RestrictedFileSecurity.AtomicMover.system(), faults);
    }
    private static SecureRandom deterministicRandom() {
        return new SecureRandom() {
            private int next;
            @Override public void nextBytes(byte[] bytes) {
                for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (next++ * 31 + 7);
            }
        };
    }
    private static CancellationToken token(AtomicBoolean cancelled) {
        return new CancellationToken() {
            @Override public boolean isCancellationRequested() { return cancelled.get(); }
            @Override public Registration onCancellation(Runnable action) { return () -> { }; }
        };
    }
    private static void assertCorrupt(RestrictedFileCredentialStore store) {
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_CORRUPT));
    }
    private static void assertInsecure(RestrictedFileCredentialStore store) {
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
    }
    private static void writeExisting(Path file, String value) throws Exception {
        Files.writeString(file, value, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static void writeFresh(Path file, String value) throws Exception {
        Files.writeString(file, value, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    private static final class SimulatedCrash extends RuntimeException { }
}
