package io.github.liumaishenjian.ccjava.cli.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider/Auth 用户级文件共用的 owner、权限、链接与 identity 安全边界。
 *
 * <p>可信 home 在构造时解析一次；后续目标只能位于其固定 {@code .cc-java} 子树。既有对象
 * 只验证而不自动修复权限。读取在打开前后复核父目录和文件 identity；写入仅使用同目录随机
 * 临时文件、force、重读验证和原子替换。任何平台证明缺失均固定失败且不包含路径或 ACL。</p>
 */
public final class RestrictedFileSecurity {
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<AclEntryPermission> OWNER_PERMISSIONS =
            Set.copyOf(EnumSet.allOf(AclEntryPermission.class));

    private final Path trustedHome;
    private final Path root;
    private final UserPrincipal expectedOwner;
    private final PlatformAccess access;

    /**
     * 从 Composition Root 已解析的 user home 创建生产安全边界。
     *
     * @param userHome 已解析且必须安全存在的用户主目录
     */
    public RestrictedFileSecurity(Path userHome) {
        this(userHome, new NioPlatformAccess());
    }

    RestrictedFileSecurity(Path userHome, PlatformAccess access) {
        Objects.requireNonNull(userHome, "userHome 不能为空");
        this.access = Objects.requireNonNull(access, "access 不能为空");
        try {
            Path logicalHome = userHome.toAbsolutePath().normalize();
            this.trustedHome = logicalHome.toRealPath();
            if (!Files.isDirectory(trustedHome, LinkOption.NOFOLLOW_LINKS)) throw unsafe();
            this.expectedOwner = owner(trustedHome);
            this.root = trustedHome.resolve(".cc-java").normalize();
        } catch (IOException failure) {
            throw unsafe();
        }
    }

    /**
     * 返回固定且不接受调用方重定向的 store 根。
     *
     * @return 位于可信用户主目录下的固定 {@code .cc-java} 根路径
     */
    public Path root() { return root; }

    /**
     * 创建缺失目录并验证固定子树；既有目录权限不被静默修改。
     *
     * @param directory 必须位于固定 store 根内的目标目录
     */
    public void ensureDirectory(Path directory) {
        Path target = child(directory);
        try {
            DirectoryIdentity homeBefore = directoryIdentity(trustedHome, false);
            Path current = root;
            int relativeNames = root.relativize(target).getNameCount();
            for (int index = -1; index < relativeNames; index++) {
                if (index >= 0) current = current.resolve(root.relativize(target).getName(index));
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    Path parent = current.getParent();
                    DirectoryIdentity parentBefore = directoryIdentity(parent, parent.startsWith(root));
                    Files.createDirectory(current, creationAttributes(true));
                    applyOwnerOnly(current, true);
                    if (!parentBefore.same(directoryIdentity(parent, parent.startsWith(root)))) throw unsafe();
                    forceDirectory(parent);
                }
                verifyDirectory(current);
            }
            if (!homeBefore.same(directoryIdentity(trustedHome, false))) throw unsafe();
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    /**
     * 创建固定普通文件；已存在时仅验证安全属性。
     *
     * @param file 必须位于固定 store 根内的目标普通文件
     */
    public void ensureFile(Path file) {
        Path target = child(file);
        ensureDirectory(target.getParent());
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                DirectoryIdentity parentBefore = directoryIdentity(target.getParent(), true);
                Files.createFile(target, creationAttributes(false));
                applyOwnerOnly(target, false);
                if (!parentBefore.same(directoryIdentity(target.getParent(), true))) throw unsafe();
                forceDirectory(target.getParent());
            }
            fileIdentity(target);
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    /**
     * 返回目标是否安全存在；存在但不可信时直接失败。
     *
     * @param file 必须位于固定 store 根内的目标普通文件
     * @return 文件不存在时为 {@code false}，安全存在时为 {@code true}
     */
    public boolean exists(Path file) {
        Path target = child(file);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            fileIdentity(target);
            return true;
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    /**
     * 在打开前后复核父目录与文件 identity，并以硬上限读取。
     *
     * @param file 必须位于固定 store 根内的目标普通文件
     * @param maximumBytes 允许读取的最大字节数
     * @return 通过 identity 与大小复核的文件内容副本
     */
    public byte[] read(Path file, int maximumBytes) {
        Path target = child(file);
        try {
            DirectoryIdentity parentBefore = directoryIdentity(target.getParent(), true);
            FileIdentity before = fileIdentity(target);
            if (before.size() < 1 || before.size() > maximumBytes) throw unsafe();
            byte[] result;
            try (FileChannel channel = FileChannel.open(
                    target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size())) {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    if (output.size() + buffer.remaining() > maximumBytes) throw unsafe();
                    output.write(buffer.array(), buffer.position(), buffer.remaining());
                    buffer.clear();
                }
                result = output.toByteArray();
            }
            FileIdentity after = fileIdentity(target);
            if (!before.same(after)
                    || !parentBefore.same(directoryIdentity(target.getParent(), true))
                    || result.length != after.size()) {
                Arrays.fill(result, (byte) 0);
                throw unsafe();
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    /**
     * 以 owner-only temp、force、严格重读和原子替换发布字节。
     *
     * @param target 必须位于固定 store 根内的最终目标文件
     * @param temporary 与目标同目录且采用固定随机名称格式的临时文件
     * @param bytes 待持久化并在发布前严格比对的字节
     * @param maximumBytes 允许写入和重读验证的最大字节数
     * @param validator 对临时文件重读内容执行严格格式验证的回调
     * @param mover 执行最终原子替换的移动策略
     */
    public void atomicWrite(Path target, Path temporary, byte[] bytes, int maximumBytes,
                            ContentValidator validator, AtomicMover mover) {
        Path fixedTarget = child(target);
        Path fixedTemporary = child(temporary);
        if (!fixedTarget.getParent().equals(fixedTemporary.getParent())
                || !fixedTemporary.getFileName().toString().matches("\\.tmp-[0-9a-f]{32}")) throw unsafe();
        if (bytes.length < 1 || bytes.length > maximumBytes) throw unsafe();
        ensureDirectory(fixedTarget.getParent());
        try {
            DirectoryIdentity parentBefore = directoryIdentity(fixedTarget.getParent(), true);
            Files.createFile(fixedTemporary, creationAttributes(false));
            applyOwnerOnly(fixedTemporary, false);
            try (FileChannel channel = FileChannel.open(fixedTemporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            byte[] reread = read(fixedTemporary, maximumBytes);
            try {
                validator.validate(reread);
                if (!Arrays.equals(bytes, reread)) throw unsafe();
            } finally {
                Arrays.fill(reread, (byte) 0);
            }
            if (!parentBefore.same(directoryIdentity(fixedTarget.getParent(), true))) throw unsafe();
            mover.move(fixedTemporary, fixedTarget);
            fileIdentity(fixedTarget);
            forceDirectory(fixedTarget.getParent());
        } catch (AtomicMoveNotSupportedException failure) {
            throw new AtomicMoveUnavailableException();
        } catch (IOException failure) {
            throw unsafe();
        } catch (RuntimeException failure) {
            if (failure instanceof SecurityException security) throw security;
            throw failure;
        } finally {
            try {
                if (Files.exists(fixedTemporary, LinkOption.NOFOLLOW_LINKS)) {
                    fileIdentity(fixedTemporary);
                    Files.delete(fixedTemporary);
                }
            } catch (IOException | RuntimeException ignored) {
                // 下一次恢复仅按固定内部名称处理残留。
            }
        }
    }

    /**
     * 安全删除固定普通文件；不存在时幂等成功。
     *
     * @param file 必须位于固定 store 根内的目标普通文件
     */
    public void delete(Path file) {
        Path target = child(file);
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            DirectoryIdentity parent = directoryIdentity(target.getParent(), true);
            fileIdentity(target);
            Files.delete(target);
            if (!parent.same(directoryIdentity(target.getParent(), true))) throw unsafe();
            forceDirectory(target.getParent());
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    /**
     * 列出经过目录 identity 复核的直接子项。
     *
     * @param directory 必须位于固定 store 根内的目标目录
     * @return 目标目录的规范化绝对直接子路径列表
     */
    public List<Path> list(Path directory) {
        Path target = child(directory);
        try {
            DirectoryIdentity before = directoryIdentity(target, true);
            List<Path> values;
            try (var stream = Files.list(target)) {
                values = stream.map(Path::toAbsolutePath).map(Path::normalize).toList();
            }
            if (!before.same(directoryIdentity(target, true))) throw unsafe();
            for (Path value : values) if (!value.getParent().equals(target)) throw unsafe();
            return values;
        } catch (IOException | RuntimeException failure) {
            throw asSecurity(failure);
        }
    }

    private Path child(Path supplied) {
        Path value = Objects.requireNonNull(supplied, "path 不能为空").toAbsolutePath().normalize();
        if (!value.startsWith(root)) throw unsafe();
        return value;
    }

    private void verifyDirectory(Path directory) throws IOException {
        directoryIdentity(directory, true);
    }

    private DirectoryIdentity directoryIdentity(Path directory, boolean restricted) throws IOException {
        rejectLinkOrReparse(directory);
        BasicFileAttributes attributes = access.basic(directory);
        if (!attributes.isDirectory()) throw unsafe();
        Path logical = directory.toAbsolutePath().normalize();
        Path noFollow = access.noFollowRealPath(directory).toAbsolutePath().normalize();
        Path followed = access.realPath(directory).toAbsolutePath().normalize();
        if (!logical.equals(noFollow) || !logical.equals(followed)) throw unsafe();
        if (restricted) verifyPermissions(directory, true);
        return new DirectoryIdentity(identity(attributes), followed);
    }

    private FileIdentity fileIdentity(Path file) throws IOException {
        rejectLinkOrReparse(file);
        BasicFileAttributes attributes = access.basic(file);
        if (!attributes.isRegularFile()) throw unsafe();
        Path logical = file.toAbsolutePath().normalize();
        if (!logical.equals(access.noFollowRealPath(file).toAbsolutePath().normalize())
                || !logical.equals(access.realPath(file).toAbsolutePath().normalize())) throw unsafe();
        Number linkCount = access.linkCount(file);
        if (linkCount != null && linkCount.longValue() != 1L) throw unsafe();
        verifyPermissions(file, false);
        return new FileIdentity(identity(attributes), attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private static Object identity(BasicFileAttributes attributes) {
        return attributes.fileKey() != null ? attributes.fileKey()
                : new FallbackIdentity(attributes.creationTime().toMillis(), attributes.isDirectory());
    }

    private void rejectLinkOrReparse(Path path) throws IOException {
        if (access.symbolicLink(path) || access.reparsePoint(path)) throw unsafe();
    }

    @SuppressWarnings("unchecked")
    private FileAttribute<?>[] creationAttributes(boolean directory) {
        PosixFileAttributeView posix = access.posix(trustedHome);
        if (posix != null) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    directory ? DIRECTORY_MODE : FILE_MODE)};
        }
        AclFileAttributeView acl = access.acl(trustedHome);
        if (acl == null) throw unsafe();
        AclEntry.Builder owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(expectedOwner).setPermissions(OWNER_PERMISSIONS);
        if (directory) owner.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        FileAttribute<List<AclEntry>> attribute = new FileAttribute<>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return List.of(owner.build()); }
        };
        return new FileAttribute<?>[]{attribute};
    }

    private void applyOwnerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = access.posix(path);
        if (posix != null) {
            posix.setPermissions(directory ? DIRECTORY_MODE : FILE_MODE);
            Files.setOwner(path, expectedOwner);
            return;
        }
        AclFileAttributeView acl = access.acl(path);
        if (acl == null) throw unsafe();
        acl.setOwner(expectedOwner);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(expectedOwner).setPermissions(OWNER_PERMISSIONS).build()));
    }

    private void verifyPermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = access.posix(path);
        if (posix != null) {
            PosixFileAttributes attributes = posix.readAttributes();
            if (!expectedOwner.equals(attributes.owner())
                    || !(directory ? DIRECTORY_MODE : FILE_MODE).equals(attributes.permissions())) throw unsafe();
            return;
        }
        AclFileAttributeView acl = access.acl(path);
        if (acl == null || !samePrincipal(expectedOwner, acl.getOwner())) throw unsafe();
        boolean ownerReadWrite = false;
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW) {
                if (!samePrincipal(expectedOwner, entry.principal())) throw unsafe();
                ownerReadWrite |= entry.permissions().contains(AclEntryPermission.READ_DATA)
                        && entry.permissions().contains(AclEntryPermission.WRITE_DATA);
            }
        }
        if (!ownerReadWrite) throw unsafe();
    }

    /**
     * 仅接受文件系统 Provider 能证明为同一身份的 principal。
     *
     * <p>Windows principal 的 {@link UserPrincipal#equals(Object)} 由 JDK Provider 按 SID
     * 身份实现；这里不得按用户名叶子、大小写或域后缀猜测等价。测试 double 亦须明确实现
     * identity equality。无法证明时返回 false，从而 fail closed。</p>
     */
    static boolean samePrincipal(UserPrincipal expected, UserPrincipal actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static UserPrincipal owner(Path path) throws IOException {
        FileOwnerAttributeView view = Files.getFileAttributeView(
                path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null || view.getOwner() == null) throw unsafe();
        return view.getOwner();
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 不支持目录 channel；文件与原子 rename 已 force。
        }
    }

    private static SecurityException asSecurity(Throwable failure) {
        return failure instanceof AtomicMoveUnavailableException unavailable ? unavailable
                : failure instanceof SecurityException security ? security : unsafe();
    }

    private static SecurityException unsafe() { return new SecurityException("AUTH_STORE_INSECURE"); }

    /** 原子 move seam；生产实现禁止非原子降级。 */
    @FunctionalInterface
    public interface AtomicMover {
        /**
         * 将临时文件原子替换为最终目标。
         *
         * @param source 待发布的同目录临时文件
         * @param target 最终目标文件
         * @throws IOException 文件系统无法完成移动时抛出
         */
        void move(Path source, Path target) throws IOException;

        /**
         * 返回 {@code ATOMIC_MOVE+REPLACE_EXISTING} 生产实现。
         *
         * @return 禁止非原子降级的生产移动策略
         */
        static AtomicMover system() {
            return (source, target) -> Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** temp 重读后的严格内容验证器。 */
    @FunctionalInterface
    public interface ContentValidator {
        /**
         * 验证从临时文件严格重读的内容。
         *
         * @param bytes 待验证的临时文件内容副本
         */
        void validate(byte[] bytes);
    }

    /** 平台不支持原子替换时的固定失败。 */
    public static final class AtomicMoveUnavailableException extends SecurityException {
        private AtomicMoveUnavailableException() { super("AUTH_STORE_INSECURE"); }
    }

    interface PlatformAccess {
        BasicFileAttributes basic(Path path) throws IOException;
        boolean symbolicLink(Path path);
        boolean reparsePoint(Path path) throws IOException;
        Path noFollowRealPath(Path path) throws IOException;
        Path realPath(Path path) throws IOException;
        Number linkCount(Path path) throws IOException;
        PosixFileAttributeView posix(Path path);
        AclFileAttributeView acl(Path path);
    }

    private static final class NioPlatformAccess implements PlatformAccess {
        @Override public BasicFileAttributes basic(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public boolean symbolicLink(Path path) { return Files.isSymbolicLink(path); }
        @Override public boolean reparsePoint(Path path) throws IOException {
            try {
                return Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
            } catch (UnsupportedOperationException unsupported) { return false; }
        }
        @Override public Path noFollowRealPath(Path path) throws IOException {
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
        @Override public Path realPath(Path path) throws IOException { return path.toRealPath(); }
        @Override public Number linkCount(Path path) throws IOException {
            try {
                Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                return value instanceof Number number ? number : null;
            } catch (UnsupportedOperationException | IllegalArgumentException unsupported) { return null; }
        }
        @Override public PosixFileAttributeView posix(Path path) {
            return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public AclFileAttributeView acl(Path path) {
            return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private record FallbackIdentity(long creationMillis, boolean directory) { }
    private record DirectoryIdentity(Object fileKey, Path realPath) {
        private boolean same(DirectoryIdentity other) {
            return Objects.equals(fileKey, other.fileKey) && realPath.equals(other.realPath);
        }
    }
    private record FileIdentity(Object fileKey, long size, long modifiedMillis) {
        private boolean same(FileIdentity other) {
            return Objects.equals(fileKey, other.fileKey)
                    && size == other.size && modifiedMillis == other.modifiedMillis;
        }
    }
}
