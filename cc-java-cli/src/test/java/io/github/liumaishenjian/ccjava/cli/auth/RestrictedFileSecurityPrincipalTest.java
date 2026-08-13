package io.github.liumaishenjian.ccjava.cli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证伪 Windows 用户名叶子或域后缀被误当成同一 ACL principal。
 */
final class RestrictedFileSecurityPrincipalTest {

    @Test
    void rejectsDifferentDomainsWithTheSameLeafUserName() {
        UserPrincipal expected = new NamedPrincipal("DOMAIN_A\\user", "sid-a");
        UserPrincipal forged = new NamedPrincipal("DOMAIN_B\\user", "sid-b");

        assertThat(RestrictedFileSecurity.samePrincipal(expected, forged)).isFalse();
    }

    @Test
    void rejectsShortLeafAndCaseFoldedGuessing() {
        UserPrincipal expected = new NamedPrincipal("DOMAIN_A\\user", "sid-a");

        assertThat(RestrictedFileSecurity.samePrincipal(expected, new NamedPrincipal("user", "sid-b"))).isFalse();
        assertThat(RestrictedFileSecurity.samePrincipal(expected,
                new NamedPrincipal("domain_a\\USER", "sid-b"))).isFalse();
    }

    @Test
    void acceptsOnlyProviderProvenIdentityEquality() {
        UserPrincipal expected = new NamedPrincipal("DOMAIN_A\\user", "sid-a");
        UserPrincipal equivalent = new NamedPrincipal("resolved-alias", "sid-a");

        assertThat(RestrictedFileSecurity.samePrincipal(expected, equivalent)).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAclRoundTripUsesProviderIdentityEquality(@TempDir Path home) throws Exception {
        UserPrincipal homeOwner = Files.getOwner(home);
        RestrictedFileSecurity security = new RestrictedFileSecurity(home);
        Path auth = security.root().resolve("auth");
        security.ensureDirectory(auth);
        Path file = auth.resolve("identity.v1.json");
        security.ensureFile(file);

        assertThat(RestrictedFileSecurity.samePrincipal(homeOwner, Files.getOwner(file))).isTrue();
        assertThat(security.exists(file)).isTrue();
    }

    private record NamedPrincipal(String name, String identity) implements UserPrincipal {
        @Override public String getName() { return name; }
        @Override public boolean equals(Object other) {
            return other instanceof NamedPrincipal principal && identity.equals(principal.identity);
        }
        @Override public int hashCode() { return identity.hashCode(); }
    }
}
