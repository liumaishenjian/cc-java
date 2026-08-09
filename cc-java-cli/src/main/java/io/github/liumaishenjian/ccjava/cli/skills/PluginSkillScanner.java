package io.github.liumaishenjian.ccjava.cli.skills;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillDescriptor;
import io.github.liumaishenjian.ccjava.domain.skill.SkillId;
import io.github.liumaishenjian.ccjava.domain.skill.SkillInvocationPolicy;
import io.github.liumaishenjian.ccjava.domain.skill.SkillResourceSnapshot;
import io.github.liumaishenjian.ccjava.domain.skill.SkillSource;
import io.github.liumaishenjian.ccjava.domain.skill.SkillToolRestriction;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从受信 immutable Plugin snapshots 构造全局命名的 Skill metadata/body/resource 绑定。
 *
 * <p>组件路径可指向单个 Skill 目录或包含多个 Skill 子目录的目录；所有文件必须仍在同一个
 * canonical content root 内且不跟随链接。全局 ID 固定为
 * {@code plugin__<plugin-id>__skills__<component-name>}；一个组件只能发布一个 Skill，避免任意
 * 本地名称改变 namespace。扫描同时冻结资源、Tool、Hook 与 Plugin/MCP 身份摘要。</p>
 *
 * @since 0.11.0
 */
public final class PluginSkillScanner {
    private static final Set<String> FIELDS = Set.of(
            "name", "description", "invocation", "allowed-tools", "resources", "hooks");

    /** 扫描 Plugin manifest 中 SKILLS 组件。任一 identity 失败使整个 composition fail closed。 */
    public PluginSkillSet scan(Path storeRoot, List<PluginSnapshot> snapshots) {
        Objects.requireNonNull(storeRoot, "storeRoot 不能为空");
        List<PluginSkillSet.Entry> entries = new ArrayList<>();
        try {
            for (PluginSnapshot snapshot : snapshots) {
                Path root = contentRoot(storeRoot, snapshot);
                for (var component : snapshot.manifest().components().stream()
                        .filter(value -> value.kind() == PluginComponentKind.SKILLS).toList()) {
                    Path candidate = root.resolve(component.logicalPath()).normalize();
                    if (!candidate.startsWith(root)) throw new IOException();
                    Path skillFile = Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                            ? candidate.resolve("SKILL.md") : candidate;
                    if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(skillFile)) throw new IOException();
                    byte[] bytes = Files.readAllBytes(skillFile);
                    if (bytes.length > FileSkillRepository.MAX_SKILL_BYTES
                            || countLines(bytes) > FileSkillRepository.MAX_SKILL_LINES) throw new IOException();
                    ParsedSkill parsed = parse(bytes);
                    Metadata metadata = parsed.metadata();
                    SkillId global = new SkillId("plugin__" + snapshot.manifest().id().value()
                            + "__skills__" + component.name());
                    List<String> resources = metadata.list("resources");
                    List<String> hooks = metadata.list("hooks");
                    SkillToolRestriction tools = metadata.present("allowed-tools")
                            ? SkillToolRestriction.declared(metadata.list("allowed-tools"))
                            : SkillToolRestriction.unspecified();
                    SkillDescriptor descriptor = new SkillDescriptor(global, metadata.scalar("description"),
                            policy(metadata.optional("invocation", "both")), SkillSource.PLUGIN,
                            "plugin/" + snapshot.safeContentId() + "/" + component.name(), sha256(bytes), tools,
                            resources, hooks);
                    List<SkillResourceSnapshot> resourceSnapshots = resources(skillFile.getParent(), resources);
                    entries.add(new PluginSkillSet.Entry(descriptor, skillFile, root,
                            parsed.body(), resourceSnapshots,
                            sha256(parsed.manifest().getBytes(StandardCharsets.UTF_8)),
                            sha256(parsed.body().getBytes(StandardCharsets.UTF_8)),
                            resourceDigest(resourceSnapshots), digestStrings(tools.toolNames()), digestStrings(hooks),
                            snapshot.fingerprint().treeDigest(), snapshot.fingerprint().manifestDigest(),
                            providerConfigDigest(snapshot)));
                }
            }
        } catch (RuntimeException | IOException failure) {
            throw new IllegalArgumentException("Plugin Skill composition 拒绝");
        }
        entries.sort(Comparator.comparing(entry -> entry.descriptor().id()));
        return new PluginSkillSet(entries);
    }

    private static Path contentRoot(Path storeRoot, PluginSnapshot snapshot) throws IOException {
        Path root = storeRoot.resolve(snapshot.manifest().id().value() + "-"
                + snapshot.safeContentId().substring(0, 16)).normalize();
        if (!root.startsWith(storeRoot) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) throw new IOException();
        return root.toRealPath();
    }

    private static ParsedSkill parse(byte[] bytes) {
        String text = decode(bytes).replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new IllegalArgumentException();
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException();
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        String current = null;
        for (String line : text.substring(4, end).split("\n", -1)) {
            if (line.startsWith("  - ") && current != null) {
                lists.computeIfAbsent(current, ignored -> new ArrayList<>()).add(line.substring(4).trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException();
            current = line.substring(0, colon).trim();
            if (!FIELDS.contains(current) || scalars.containsKey(current) || lists.containsKey(current)) {
                throw new IllegalArgumentException();
            }
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) lists.put(current, new ArrayList<>()); else scalars.put(current, value);
        }
        if (!scalars.containsKey("name") || !scalars.containsKey("description")) throw new IllegalArgumentException();
        return new ParsedSkill(new Metadata(scalars, lists), text.substring(4, end) + "\n",
                text.substring(end + 5));
    }

    private static List<SkillResourceSnapshot> resources(Path directory, List<String> names) throws IOException {
        List<SkillResourceSnapshot> snapshots = new ArrayList<>();
        long total = 0;
        for (String name : names) {
            Path target = directory.resolve(name).normalize();
            if (!target.startsWith(directory) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)) throw new IOException();
            byte[] bytes = Files.readAllBytes(target);
            total += bytes.length;
            if (bytes.length > FileSkillRepository.MAX_RESOURCE_BYTES
                    || total > FileSkillRepository.MAX_RESOURCES_BYTES) throw new IOException();
            snapshots.add(new SkillResourceSnapshot(name, sha256(bytes), decode(bytes)));
        }
        return List.copyOf(snapshots);
    }

    private static String resourceDigest(List<SkillResourceSnapshot> resources) {
        return digestStrings(resources.stream()
                .map(resource -> resource.logicalName() + "\0" + resource.contentDigest()).toList());
    }

    private static String providerConfigDigest(PluginSnapshot snapshot) {
        return digestStrings(snapshot.manifest().components().stream()
                .filter(value -> value.kind() == PluginComponentKind.TOOL_PROVIDER)
                .map(value -> value.name() + "\0" + value.configDigest()).sorted().toList());
    }

    private static String decode(byte[] bytes) {
        try {
            var decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            if (decoded.length() > 0 && decoded.charAt(0) == '﻿') throw new IllegalArgumentException();
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Plugin Skill UTF-8 非法", invalid);
        }
    }

    private static int countLines(byte[] bytes) {
        if (bytes.length == 0) return 0;
        int lines = 1;
        for (byte value : bytes) if (value == '\n') lines++;
        return lines;
    }

    static String digestStrings(List<String> values) {
        MessageDigest digest = newDigest();
        for (String value : values.stream().sorted().toList()) {
            digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(newDigest().digest(bytes)); }
    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static SkillInvocationPolicy policy(String value) {
        return switch (value) {
            case "explicit" -> SkillInvocationPolicy.EXPLICIT;
            case "model" -> SkillInvocationPolicy.MODEL;
            case "both" -> SkillInvocationPolicy.BOTH;
            default -> throw new IllegalArgumentException();
        };
    }

    private record ParsedSkill(Metadata metadata, String manifest, String body) { }

    private record Metadata(Map<String, String> scalars, Map<String, List<String>> lists) {
        private String scalar(String key) { return Objects.requireNonNull(scalars.get(key)); }
        private String optional(String key, String fallback) { return scalars.getOrDefault(key, fallback); }
        private boolean present(String key) { return scalars.containsKey(key) || lists.containsKey(key); }
        private List<String> list(String key) { return List.copyOf(lists.getOrDefault(key, List.of())); }
    }
}
