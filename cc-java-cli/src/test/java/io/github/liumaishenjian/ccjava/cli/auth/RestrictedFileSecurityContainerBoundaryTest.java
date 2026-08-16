package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 证伪共享 {@code .cc-java} 容器被错误提升为 credential owner-only 目录。
 */
final class RestrictedFileSecurityContainerBoundaryTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void platformDoubleAllowsSharedRootButKeepsAuthDescendantsRestricted(@TempDir Path temporary) throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path root = Files.createDirectory(home.resolve(".cc-java"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        RestrictedFileSecurity security = new RestrictedFileSecurity(home,
                new NioPlatformDouble(Files.getOwner(home)));
        RestrictedFileCredentialStore store = new RestrictedFileCredentialStore(
                security, Clock.systemUTC(), new SecureRandom(),
                RestrictedFileSecurity.AtomicMover.system(), RestrictedFileCredentialStore.FaultInjector.none());

        assertThat(store.snapshot(CancellationToken.none()).profiles()).isEmpty();
        assertThat(Files.getPosixFilePermissions(root.resolve("auth")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));

        Files.setPosixFilePermissions(root.resolve("auth"), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void currentPrincipalLookupIsIndependentFromHomeOwnerAndStillOwnsSharedRoot(@TempDir Path temporary)
            throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path root = Files.createDirectory(home.resolve(".cc-java"));
        UserPrincipal currentPrincipal = Files.getOwner(root);
        UserPrincipal differentPrincipal = () -> "different-principal";
        NioPlatformDouble platform = new NioPlatformDouble(currentPrincipal);
        platform.reportOwner(home, differentPrincipal);
        RestrictedFileCredentialStore store = new RestrictedFileCredentialStore(
                new RestrictedFileSecurity(home, platform), Clock.systemUTC(), new SecureRandom(),
                RestrictedFileSecurity.AtomicMover.system(), RestrictedFileCredentialStore.FaultInjector.none());

        assertThat(platform.posix(home).readAttributes().owner()).isEqualTo(differentPrincipal);
        assertThat(platform.lookupPrincipalByName(home, System.getProperty("user.name")))
                .isEqualTo(currentPrincipal);
        assertThat(store.snapshot(CancellationToken.none()).profiles()).isEmpty();

        platform.reportOwner(root, differentPrincipal);
        assertThatThrownBy(() -> store.snapshot(CancellationToken.none()))
                .isInstanceOfSatisfying(ProviderAuthException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ProviderAuthException.Code.AUTH_STORE_INSECURE));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsSharedRootReadPrincipalDoesNotRelaxAuthOrCredentialAcl(@TempDir Path home) throws Exception {
        UserPrincipal owner = Files.getOwner(home);
        UserPrincipal readPrincipal = lookupCodexSandboxUsers(home);
        assumeTrue(readPrincipal != null, "当前 Windows 没有 CodexSandboxUsers principal");
        Path root = Files.createDirectory(home.resolve(".cc-java"));
        setAcl(root, owner, readPrincipal, true);
        RestrictedFileSecurity security = new RestrictedFileSecurity(home);
        Path auth = root.resolve("auth");

        security.ensureDirectory(auth);
        assertThat(security.list(auth)).isEmpty();

        setAcl(auth, owner, readPrincipal, true);
        assertThatThrownBy(() -> security.ensureDirectory(auth))
                .isInstanceOfSatisfying(SecurityException.class,
                        failure -> assertThat(failure.getMessage()).isEqualTo("AUTH_STORE_INSECURE"));

        setAcl(auth, owner, null, false);
        Path credential = auth.resolve("profiles.v1.json");
        security.ensureFile(credential);
        setAcl(credential, owner, readPrincipal, false);
        assertThatThrownBy(() -> security.exists(credential))
                .isInstanceOfSatisfying(SecurityException.class,
                        failure -> assertThat(failure.getMessage()).isEqualTo("AUTH_STORE_INSECURE"));
    }

    private static UserPrincipal lookupCodexSandboxUsers(Path path) {
        UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
        for (String name : List.of("CodexSandboxUsers", ".\\CodexSandboxUsers")) {
            try {
                return lookup.lookupPrincipalByGroupName(name);
            } catch (IOException ignored) {
                // 当前主机未安装该可选本地组时由 selector 跳过真实 ACL 场景。
            }
        }
        return null;
    }

    private static void setAcl(Path path, UserPrincipal owner, UserPrincipal extra, boolean directory)
            throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        view.setOwner(owner);
        List<AclEntry> entries = new ArrayList<>();
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        if (extra != null) {
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(extra)
                    .setPermissions(directory
                            ? EnumSet.of(AclEntryPermission.LIST_DIRECTORY, AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE,
                            AclEntryPermission.EXECUTE)
                            : EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE))
                    .build());
        }
        view.setAcl(entries);
    }

    /** 使用真实 NIO 操作但显式注入平台 seam，避免非 Windows 测试依赖 ACL provider。 */
    private static final class NioPlatformDouble implements RestrictedFileSecurity.PlatformAccess {
        private final UserPrincipal currentPrincipal;
        private final Map<Path, UserPrincipal> reportedOwners = new HashMap<>();

        private NioPlatformDouble(UserPrincipal currentPrincipal) {
            this.currentPrincipal = currentPrincipal;
        }

        private void reportOwner(Path path, UserPrincipal owner) {
            reportedOwners.put(path.toAbsolutePath().normalize(), owner);
        }

        @Override public BasicFileAttributes basic(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public boolean symbolicLink(Path path) { return Files.isSymbolicLink(path); }
        @Override public boolean reparsePoint(Path path) throws IOException {
            try {
                return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
            } catch (UnsupportedOperationException unsupported) {
                return false;
            }
        }
        @Override public Path noFollowRealPath(Path path) throws IOException {
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
        @Override public Path realPath(Path path) throws IOException { return path.toRealPath(); }
        @Override public Number linkCount(Path path) throws IOException {
            Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return value instanceof Number number ? number : null;
        }
        @Override public PosixFileAttributeView posix(Path path) {
            return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public AclFileAttributeView acl(Path path) {
            return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public UserPrincipal lookupPrincipalByName(Path path, String name) {
            return currentPrincipal;
        }
    }
}
