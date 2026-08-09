package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code registry.v1} 的严格、有界、稳定排序编码器。
 *
 * <p>该索引只保存 Plugin 内容身份，不承担信任、迁移或崩溃恢复。安装和卸载 Adapter
 * 负责以 staged/backup/atomic replace 发布本类型生成的完整快照，因而单项变更不会丢失
 * 其他 Plugin。</p>
 *
 * @since 0.11.0
 */
final class PluginRegistryIndex {
    static final int MAX_BYTES = 64 * 1_024;
    static final int MAX_ENTRIES = 256;

    private PluginRegistryIndex() { }

    static List<Entry> read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_BYTES) throw new IOException("registry invalid");
        byte[] bytes = Files.readAllBytes(path);
        String text = decode(bytes);
        if (!text.isEmpty() && !text.endsWith("\n")) throw new IOException("registry invalid");
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) throw new IOException("registry invalid");
            Entry entry;
            try { entry = new Entry(fields[0], fields[1], fields[2]); }
            catch (RuntimeException invalid) { throw new IOException("registry invalid", invalid); }
            if (entries.putIfAbsent(entry.id(), entry) != null || entries.size() > MAX_ENTRIES) {
                throw new IOException("registry invalid");
            }
        }
        return entries.values().stream().sorted(Comparator.comparing(Entry::id)).toList();
    }

    static byte[] replacing(List<Entry> current, PluginSnapshot snapshot) throws IOException {
        Map<String, Entry> entries = map(current);
        Entry replacement = Entry.from(snapshot);
        entries.put(replacement.id(), replacement);
        return encode(entries.values());
    }

    static byte[] removing(List<Entry> current, PluginId pluginId) throws IOException {
        Map<String, Entry> entries = map(current);
        if (entries.remove(pluginId.value()) == null) throw new IOException("registry entry missing");
        return encode(entries.values());
    }

    static String directoryName(String id, String treeDigest) {
        return id + "-" + treeDigest.substring(0, 16);
    }

    private static Map<String, Entry> map(List<Entry> current) throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Entry entry : current) {
            if (entries.putIfAbsent(entry.id(), entry) != null) throw new IOException("registry duplicate");
        }
        return entries;
    }

    private static byte[] encode(Iterable<Entry> values) throws IOException {
        List<Entry> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(Comparator.comparing(Entry::id));
        if (sorted.size() > MAX_ENTRIES) throw new IOException("registry limit");
        StringBuilder text = new StringBuilder();
        for (Entry entry : sorted) {
            text.append(entry.id()).append('\t').append(entry.version()).append('\t')
                    .append(entry.treeDigest()).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("registry limit");
        return bytes;
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("registry invalid", invalid);
        }
    }

    record Entry(String id, String version, String treeDigest) {
        Entry {
            new PluginId(id);
            if (version == null || version.isBlank() || version.length() > 64
                    || version.indexOf('\t') >= 0 || version.indexOf('\n') >= 0 || version.indexOf('\r') >= 0
                    || treeDigest == null || !treeDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("registry entry invalid");
            }
        }

        static Entry from(PluginSnapshot snapshot) {
            return new Entry(snapshot.manifest().id().value(), snapshot.manifest().version(),
                    snapshot.fingerprint().treeDigest());
        }
    }
}
