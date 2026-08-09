package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentDescriptor;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginComponentKind;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginId;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将有界 UTF-8 {@code plugin.json} 解析为 strict v1 Domain manifest。
 *
 * <p>未知字段、重复键、错误类型和任意可执行 Provider 声明均 fail closed；异常不包含输入文本。</p>
 *
 * @since 0.11.0
 */
public final class PluginManifestParser {
    public static final int MAX_BYTES = 64 * 1_024;
    private static final Set<String> ROOT = Set.of(
            "schemaVersion", "id", "version", "description", "requiresHost", "components");
    private static final Set<String> COMPONENTS = Set.of(
            "skills", "hooks", "mcpServers", "toolProviders");
    private static final Set<String> BASIC = Set.of("name", "path");
    private static final Set<String> SERVER = Set.of("name", "path");
    private static final Set<String> PROVIDER = Set.of(
            "name", "path", "type", "mcpServers", "configDigest");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 解析 manifest；所有失败只暴露结构化错误码。 */
    public ParsedPluginManifest parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw failure(bytes == null ? PluginErrorCode.MANIFEST_INVALID : PluginErrorCode.MANIFEST_TOO_LARGE);
        }
        try {
            JsonNode root = JSON.readTree(bytes);
            requireObject(root, ROOT);
            int schemaVersion = integer(root, "schemaVersion");
            JsonNode componentRoot = root.get("components");
            requireObject(componentRoot, COMPONENTS);
            var components = new ArrayList<PluginComponentDescriptor>();
            parseBasics(componentRoot, "skills", PluginComponentKind.SKILLS, components);
            parseBasics(componentRoot, "hooks", PluginComponentKind.HOOKS, components);
            parseBasics(componentRoot, "mcpServers", PluginComponentKind.MCP_SERVER, components);
            parseProviders(componentRoot, components);
            if (components.size() > 128) throw failure(PluginErrorCode.COMPONENT_LIMIT_EXCEEDED);
            PluginManifest manifest = new PluginManifest(
                    schemaVersion,
                    new PluginId(text(root, "id")),
                    text(root, "version"),
                    optionalText(root, "description"),
                    optionalText(root, "requiresHost"),
                    components);
            return new ParsedPluginManifest(manifest, digest(bytes));
        } catch (PluginBoundaryException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(PluginErrorCode.MANIFEST_INVALID);
        }
    }

    private static void parseBasics(JsonNode root, String field, PluginComponentKind kind,
            List<PluginComponentDescriptor> output) {
        JsonNode array = root.get(field);
        if (array == null) return;
        if (!array.isArray()) throw failure(PluginErrorCode.MANIFEST_INVALID);
        for (JsonNode item : array) {
            requireObject(item, BASIC);
            output.add(new PluginComponentDescriptor(
                    kind, text(item, "name"), text(item, "path"), null, List.of(), null));
            if (output.size() > 128) throw failure(PluginErrorCode.COMPONENT_LIMIT_EXCEEDED);
        }
    }

    private static void parseProviders(JsonNode root, List<PluginComponentDescriptor> output) {
        JsonNode array = root.get("toolProviders");
        if (array == null) return;
        if (!array.isArray()) throw failure(PluginErrorCode.MANIFEST_INVALID);
        for (JsonNode item : array) {
            requireObject(item, PROVIDER);
            JsonNode references = item.get("mcpServers");
            if (references == null || !references.isArray()) throw failure(PluginErrorCode.MANIFEST_INVALID);
            var names = new ArrayList<String>();
            for (JsonNode reference : references) {
                if (!reference.isTextual()) throw failure(PluginErrorCode.MANIFEST_INVALID);
                names.add(reference.stringValue());
            }
            output.add(new PluginComponentDescriptor(
                    PluginComponentKind.TOOL_PROVIDER,
                    text(item, "name"),
                    text(item, "path"),
                    text(item, "type"),
                    names,
                    text(item, "configDigest")));
            if (output.size() > 128) throw failure(PluginErrorCode.COMPONENT_LIMIT_EXCEEDED);
        }
    }

    private static void requireObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()
                || node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw failure(PluginErrorCode.MANIFEST_INVALID);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw failure(PluginErrorCode.MANIFEST_INVALID);
        return value.stringValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isTextual()) throw failure(PluginErrorCode.MANIFEST_INVALID);
        return value.stringValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure(PluginErrorCode.MANIFEST_INVALID);
        }
        return value.intValue();
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static PluginBoundaryException failure(PluginErrorCode code) {
        return new PluginBoundaryException(code);
    }

    /** @param manifest strict Domain manifest @param manifestDigest exact input SHA-256 */
    public record ParsedPluginManifest(PluginManifest manifest, String manifestDigest) {
        public ParsedPluginManifest {
            if (manifest == null || manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Parsed manifest 非法");
            }
        }
    }
}
