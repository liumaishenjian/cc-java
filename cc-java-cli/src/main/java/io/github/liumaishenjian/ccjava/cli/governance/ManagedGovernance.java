package io.github.liumaishenjian.ccjava.cli.governance;

import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyResolution;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyResolver;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyStatus;
import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyValue;
import io.github.liumaishenjian.ccjava.domain.governance.FeatureGate;
import io.github.liumaishenjian.ccjava.domain.governance.FeatureStability;
import io.github.liumaishenjian.ccjava.domain.governance.ManagedPolicyProvenance;
import io.github.liumaishenjian.ccjava.protocol.ProtocolFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 固定本机管理员 root 的 Managed Policy、LKG 与 Feature Gate 生产装配。
 *
 * <p>格式只表达 deny/require，不接受项目、用户或 Session 放宽。current 通过严格解析后原子刷新
 * LKG；声明存在但安全字段损坏且无可信 LKG 时返回 fail-closed。路径、正文和异常不进入诊断。</p>
 *
 * @since 0.1.0
 */
public final class ManagedGovernance {
    private static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> KEYS = Set.of(
            "schema", "required-sandbox", "network-denied", "denied-features",
            "stable-gates", "experimental-gates");
    private final Path root;
    private final Path current;
    private final Path lkg;
    private final ManagedPolicyResolution policy;
    private final List<FeatureGate> gates;

    private ManagedGovernance(Path root, ManagedPolicyResolution policy, List<FeatureGate> gates) {
        this.root = root;
        this.current = root.resolve("policy.v1");
        this.lkg = root.resolve("policy.lkg.v1");
        this.policy = policy;
        this.gates = List.copyOf(gates);
    }

    /**
     * 从机器级管理员目录加载；普通用户 home 永远不能冒充 Managed provenance。
     *
     * <p>Windows 固定使用 {@code %ProgramData%/cc-java/managed}，POSIX 使用
     * {@code /etc/cc-java/managed}。目录不存在表示未声明策略；测试只能使用包内
     * {@code loadForTesting(Path)} seam。</p>
     *
     * @param ignoredUserHome 兼容既有装配签名的用户目录；不会参与机器策略定位
     * @return 已完成来源验证、解析和 LKG 选择的治理快照
     */
    public static ManagedGovernance production(Path ignoredUserHome) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path root;
        if (os.contains("win")) {
            String programData = System.getenv("ProgramData");
            if (programData == null || programData.isBlank()) {
                return absentMachinePolicy();
            }
            root = Path.of(programData, "cc-java", "managed");
        } else {
            root = Path.of("/etc", "cc-java", "managed");
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return absentMachinePolicy();
        if (!trustedMachineSource(root)) return untrustedMachinePolicy(root);
        return loadValidated(root);
    }

    private static ManagedGovernance absentMachinePolicy() {
        return new ManagedGovernance(Path.of(".").toAbsolutePath().normalize(),
                new ManagedPolicyResolution(Optional.empty(), ManagedPolicyStatus.ABSENT), List.of());
    }

    private static ManagedGovernance untrustedMachinePolicy(Path root) {
        return new ManagedGovernance(root.toAbsolutePath().normalize(),
                new ManagedPolicyResolution(Optional.empty(), ManagedPolicyStatus.FAIL_CLOSED), List.of());
    }

    /**
     * 保守证明机器级来源不可由当前普通用户修改；无法读取 owner/mode/ACL 就视为不可信。
     */
    static boolean trustedMachineSource(Path root) {
        try {
            Path checked = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(checked) || !Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)
                    || !checked.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(checked)) return false;
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (!os.contains("win")) {
                var attributes = Files.readAttributes(checked,
                        java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return "root".equals(attributes.owner().getName())
                        && !attributes.permissions().contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                        && !attributes.permissions().contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            }
            var owner = Files.getOwner(checked, LinkOption.NOFOLLOW_LINKS);
            String ownerName = owner.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(ownerName.endsWith("\\administrators") || ownerName.endsWith("\\system")
                    || "administrators".equals(ownerName) || "system".equals(ownerName))) return false;
            var view = Files.getFileAttributeView(checked, java.nio.file.attribute.AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) return false;
            for (var entry : view.getAcl()) {
                if (entry.type() != java.nio.file.attribute.AclEntryType.ALLOW) continue;
                String principal = entry.principal().getName().toLowerCase(java.util.Locale.ROOT);
                boolean broad = principal.endsWith("\\users") || principal.endsWith("\\everyone")
                        || principal.endsWith("\\authenticated users") || "users".equals(principal)
                        || "everyone".equals(principal) || "authenticated users".equals(principal);
                if (broad && entry.permissions().stream().anyMatch(ManagedGovernance::isWritePermission)) return false;
            }
            return true;
        } catch (Exception unprovable) {
            return false;
        }
    }

    private static boolean isWritePermission(java.nio.file.attribute.AclEntryPermission permission) {
        return switch (permission) {
            case WRITE_DATA, APPEND_DATA, WRITE_NAMED_ATTRS, WRITE_ATTRIBUTES,
                    DELETE, DELETE_CHILD, WRITE_ACL, WRITE_OWNER -> true;
            default -> false;
        };
    }

    /** 测试专用 seam；不执行 production machine-source trust 判定。 */
    static ManagedGovernance loadForTesting(Path rootValue) {
        return loadValidated(rootValue.toAbsolutePath().normalize());
    }

    private static ManagedGovernance loadValidated(Path root) {
        Path current = root.resolve("policy.v1");
        Path lkg = root.resolve("policy.lkg.v1");
        boolean declared = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        Optional<Parsed> currentValue = parse(current, false);
        Optional<Parsed> lkgValue = parse(lkg, true);
        ManagedPolicyResolution resolution = new ManagedPolicyResolver().resolve(
                currentValue.map(Parsed::policy), lkgValue.map(Parsed::policy), declared, true);
        if (currentValue.isPresent()) {
            try {
                Files.createDirectories(root);
                if (Files.isSymbolicLink(root)) throw new IOException();
                Path staged = root.resolve("policy.lkg.tmp");
                Files.write(staged, currentValue.orElseThrow().canonical(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE);
                try (var channel = java.nio.channels.FileChannel.open(staged,
                        java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(staged, lkg, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // current 仍可信；LKG 刷新失败只进入固定状态，不扩大策略。
            }
        }
        List<FeatureGate> gates = resolution.value().isPresent()
                ? (currentValue.isPresent() ? currentValue.orElseThrow().gates()
                : lkgValue.map(Parsed::gates).orElse(List.of()))
                : List.of();
        return new ManagedGovernance(root, resolution, gates);
    }

    /**
     * 返回可信 current/LKG 选择或 fail-closed 状态。
     *
     * @return 不含路径与正文的 Managed Policy 解析结果
     */
    public ManagedPolicyResolution policy() { return policy; }

    /**
     * 返回由可信策略声明且只可收窄的 Feature Gate。
     *
     * @return 不可变 Feature Gate 列表
     */
    public List<FeatureGate> gates() { return gates; }

    /**
     * 由稳定默认能力减去 Managed deny；fail-closed 时不协商任何可选能力。
     *
     * @param defaults 宿主原本支持的稳定协议能力
     * @return 应向客户端公开的不可变能力集合
     */
    public Set<ProtocolFeature> negotiatedFeatures(Set<ProtocolFeature> defaults) {
        if (policy.status() == ManagedPolicyStatus.FAIL_CLOSED) return Set.of();
        EnumSet<ProtocolFeature> result = defaults.isEmpty()
                ? EnumSet.noneOf(ProtocolFeature.class) : EnumSet.copyOf(defaults);
        policy.value().ifPresent(value -> value.deniedFeatures().forEach(id -> {
            try { result.remove(ProtocolFeature.valueOf(id)); } catch (IllegalArgumentException ignored) { }
        }));
        gates.stream().filter(gate -> !gate.enabled()).forEach(gate -> {
            try { result.remove(ProtocolFeature.valueOf(gate.id().toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_').replace('-', '_'))); } catch (IllegalArgumentException ignored) { }
        });
        return Set.copyOf(result);
    }

    /**
     * 生成不含路径、正文或 digest 的 doctor 投影。
     *
     * @return 隐私安全的治理状态快照
     */
    public GovernanceSnapshot snapshot() {
        return new GovernanceSnapshot(policy.status(),
                policy.value().map(value -> value.provenance().lastKnownGood()).orElse(false), gates);
    }

    private static Optional<Parsed> parse(Path file, boolean asLkg) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent == null || Files.isSymbolicLink(parent)
                    || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || !parent.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(parent)
                    || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(file.toAbsolutePath().normalize())
                    || Files.size(file) > MAX_BYTES) return Optional.empty();
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split < 1) return Optional.empty();
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if (!KEYS.contains(key) || values.putIfAbsent(key, value) != null) return Optional.empty();
            }
            if (!"1".equals(values.get("schema"))) return Optional.empty();
            boolean sandbox = bool(values.getOrDefault("required-sandbox", "false"));
            boolean network = bool(values.getOrDefault("network-denied", "false"));
            Set<String> denied = csv(values.getOrDefault("denied-features", ""));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            ManagedPolicyValue policy = new ManagedPolicyValue(denied, sandbox, network,
                    new ManagedPolicyProvenance(1, digest, Instant.now(), true, asLkg));
            ArrayList<FeatureGate> gates = new ArrayList<>();
            csv(values.getOrDefault("stable-gates", "")).forEach(id ->
                    gates.add(new FeatureGate(id, !denied.contains(id), FeatureStability.STABLE)));
            csv(values.getOrDefault("experimental-gates", "")).forEach(id ->
                    gates.add(new FeatureGate(id, !denied.contains(id), FeatureStability.EXPERIMENTAL)));
            return Optional.of(new Parsed(policy, gates, bytes));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException();
    }

    private static Set<String> csv(String value) {
        if (value.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String item : value.split(",")) {
            String id = item.trim();
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || !result.add(id))
                throw new IllegalArgumentException();
        }
        return Set.copyOf(result);
    }

    private record Parsed(ManagedPolicyValue policy, List<FeatureGate> gates, byte[] canonical) {
        private Parsed { gates = List.copyOf(gates); canonical = canonical.clone(); }
        @Override public byte[] canonical() { return canonical.clone(); }
    }

    /**
     * Governance 的隐私安全只读投影。
     *
     * @param status current/LKG/absent/fail-closed 状态
     * @param usingLkg 是否正在使用 last-known-good
     * @param gates 已解析且只可收窄的 feature gates
     */
    public record GovernanceSnapshot(
            ManagedPolicyStatus status, boolean usingLkg, List<FeatureGate> gates) {
        /** 冻结 Gate 列表，防止 doctor 投影被调用方修改。 */
        public GovernanceSnapshot { gates = List.copyOf(gates); }
    }
}
