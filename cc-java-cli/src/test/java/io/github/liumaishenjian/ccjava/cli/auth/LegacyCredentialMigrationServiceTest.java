package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinitionStore;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyCredentialMigrationServiceTest {
    @TempDir Path temporary;

    @Test
    void explicitMigrationPublishesBothStoresAndKeepsLegacyBytesExactlyEqual() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        Path config = Files.createDirectories(repository.resolve("config"))
                .resolve("provider.local.properties");
        Files.writeString(config, "# sentinel layout\r\nopenai.base-url=https://legacy.example/v1\r\n"
                + "openai.api-key=legacy-sentinel-secret\r\nopenai.model=legacy-model\r\n",
                StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(config);
        Path home = Files.createDirectory(temporary.resolve("home"));
        RestrictedFileCredentialStore credentials = new RestrictedFileCredentialStore(home);
        ProviderDefinitionStore definitions = new ProviderDefinitionStore(home);
        LegacyCredentialMigrationService service = new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repository), definitions, credentials);

        LegacyCredentialMigrationService.MigrationResult result = service.migrate(
                "migrated", "personal", true, CancellationToken.none());

        assertThat(result.code()).isEqualTo("MIGRATED_COPY_VERIFIED");
        assertThat(Files.readAllBytes(config)).containsExactly(before);
        assertThat(definitions.snapshot(CancellationToken.none()).catalog().require("migrated").models())
                .containsExactly("legacy-model");
        CredentialProfile profile = credentials.snapshot(CancellationToken.none())
                .find("migrated", "personal").orElseThrow();
        try (SecretMaterial secret = credentials.readSecret(
                (SecretRef.Store) profile.secretRef(), CancellationToken.none())) {
            char[] chars = secret.copyChars();
            try { assertThat(chars).containsExactly("legacy-sentinel-secret".toCharArray()); }
            finally { Arrays.fill(chars, '\0'); }
        }
        assertThat(result.toString()).doesNotContain("legacy-sentinel-secret");
    }

    @Test
    void missingPartialAndExistingTargetFailWithoutChangingLegacy() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        Path config = Files.createDirectories(repository.resolve("config"))
                .resolve("provider.local.properties");
        Files.writeString(config, "openai.base-url=https://legacy.example/v1\nopenai.model=model\n",
                StandardCharsets.UTF_8);
        byte[] partial = Files.readAllBytes(config);
        Path home = Files.createDirectory(temporary.resolve("home"));
        LegacyCredentialMigrationService service = new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repository), new ProviderDefinitionStore(home),
                new RestrictedFileCredentialStore(home));
        assertThatThrownBy(() -> service.migrate("target", "profile", false, CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure -> assertThat(failure.code())
                        .isEqualTo(ProviderAuthException.Code.LEGACY_CONFIGURATION_INCOMPLETE));
        assertThat(Files.readAllBytes(config)).containsExactly(partial);

        Files.writeString(config, "openai.base-url=https://legacy.example/v1\n"
                + "openai.api-key=secret\nopenai.model=model\n", StandardCharsets.UTF_8);
        byte[] complete = Files.readAllBytes(config);
        ProviderDefinitionStore definitions = new ProviderDefinitionStore(home);
        definitions.add(new io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition(
                "target", io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition.Kind.OPENAI_COMPATIBLE,
                "Existing", java.net.URI.create("https://existing.example/v1"),
                io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,
                java.util.List.of("model"), "model", java.util.Map.of(), java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(10)), 0, CancellationToken.none());
        LegacyCredentialMigrationService conflicting = new LegacyCredentialMigrationService(
                new LegacyProviderConfigurationReader(repository), definitions, new RestrictedFileCredentialStore(home));
        assertThatThrownBy(() -> conflicting.migrate("target", "profile", false, CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class, failure -> assertThat(failure.code())
                        .isEqualTo(ProviderAuthException.Code.LEGACY_MIGRATION_CONFLICT));
        assertThat(Files.readAllBytes(config)).containsExactly(complete);
    }

    @Test
    void rejectsSymlinkAndOversizeWithoutReadingAsMigrationSource() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repository"));
        Path configDir = Files.createDirectories(repository.resolve("config"));
        Path outside = temporary.resolve("outside.properties");
        Files.writeString(outside, "openai.api-key=sentinel\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(configDir.resolve("provider.local.properties"), outside);
            assertThatThrownBy(() -> new LegacyProviderConfigurationReader(repository).read())
                    .isInstanceOf(ProviderAuthException.class);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // 当前文件系统不支持 symlink；oversize 仍覆盖 bounded read。
        }
        Files.deleteIfExists(configDir.resolve("provider.local.properties"));
        byte[] bytes = new byte[16 * 1024 + 1];
        Arrays.fill(bytes, (byte) 'x');
        Files.write(configDir.resolve("provider.local.properties"), bytes);
        assertThatThrownBy(() -> new LegacyProviderConfigurationReader(repository).read())
                .isInstanceOf(ProviderAuthException.class);
    }
}
