package io.github.liumaishenjian.ccjava.cli.settings;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnostic;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticCode;
import io.github.liumaishenjian.ccjava.domain.settings.ConfigurationDiagnosticSeverity;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRule;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleDefinition;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredPermissionRuleRemoval;
import io.github.liumaishenjian.ccjava.domain.settings.DeclaredSettings;
import io.github.liumaishenjian.ccjava.domain.settings.SettingPath;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsRevision;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceId;
import io.github.liumaishenjian.ccjava.domain.settings.SettingsSourceSnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings schema v1 的无副作用严格 JSON 字节解析器。
 *
 * <p>调用方负责文件读取、真实路径与刷新发布。本类只接受已经有界的字节和安全来源标识；每个
 * 来源只有完整通过 JSON、字段和受信 Tool schema 校验后才产生快照。失败诊断不携带正文、路径、
 * 凭证、端点、选择器或底层异常信息。</p>
 *
 * @since 0.8.0
 */
public final class SettingsV1SourceParser {
    /** 单个 Settings 文件的最大 UTF-8 字节数。 */
    public static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_MEMBERS = 128;
    private static final int MAX_LIST_ITEMS = 64;
    private static final int MAX_STRING_CODE_POINTS = 4_096;
    private static final int MAX_PERMISSION_RULES = 32;
    private static final int MAX_ENABLED_TOOLS = 64;
    private static final int MAX_TOOL_CONFIGS = 32;
    private static final int MAX_TOOL_CONFIG_MEMBERS = 16;
    private static final int MAX_COMPACT_INSTRUCTIONS = 16;
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "model", "permission", "tools", "context", "diagnostics");
    private static final Set<String> MODEL_FIELDS = Set.of("name");
    private static final Set<String> PERMISSION_FIELDS = Set.of("mode", "rules");
    private static final Set<String> TOOLS_FIELDS = Set.of("enabled", "config");
    private static final Set<String> CONTEXT_FIELDS = Set.of("compactInstructions");
    private static final Set<String> DIAGNOSTICS_FIELDS = Set.of("verbosity");
    private static final Set<String> RULE_FIELDS = Set.of(
            "ruleId", "decision", "effect", "tool", "toolSource", "selector");
    private static final Set<String> RULE_REMOVAL_FIELDS = Set.of("remove");

    private final JsonFactory jsonFactory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_BYTES)
                    .maxNestingDepth(MAX_DEPTH + 1)
                    .maxStringLength(MAX_STRING_CODE_POINTS)
                    .maxNameLength(128)
                    .maxTokenCount(4_096)
                    .build())
            .build();
    private final ObjectMapper mapper = JsonMapper.builder(jsonFactory).build();
    private final Map<String, Set<String>> toolConfigurationFields;

    /**
     * 使用没有受支持配置字段的 builtin Tool 注册表创建解析器。
     *
     * @param builtinTools 受信注册表提供的内置 Tool 名集合
     */
    public SettingsV1SourceParser(Set<String> builtinTools) {
        this(indexBuiltinTools(builtinTools));
    }

    /**
     * 使用受信 builtin Tool 名和各自允许配置字段创建解析器。
     *
     * <p>该注册表来自应用代码而非 Settings 输入，因此不允许 Settings 增加 Tool 或扩大其配置面。</p>
     *
     * @param toolConfigurationFields Tool 名至允许 scalar 配置字段的注册表
     */
    public SettingsV1SourceParser(Map<String, Set<String>> toolConfigurationFields) {
        Objects.requireNonNull(toolConfigurationFields, "toolConfigurationFields 不能为空");
        LinkedHashMap<String, Set<String>> copied = new LinkedHashMap<>();
        toolConfigurationFields.forEach((tool, fields) -> {
            requireRegisteredName(tool, "Tool 名");
            Objects.requireNonNull(fields, "Tool 配置字段不能为空");
            fields.forEach(field -> requireRegisteredName(field, "Tool 配置字段"));
            copied.put(tool, Set.copyOf(fields));
        });
        this.toolConfigurationFields = Collections.unmodifiableMap(copied);
    }

    /**
     * 解析一个完整来源。
     *
     * @param sourceId 不含路径与正文的来源标识
     * @param bytes 来源原始字节
     * @return 成功时含完整快照，失败时只含一个固定分类诊断
     */
    public ParseResult parse(SettingsSourceId sourceId, byte[] bytes) {
        Objects.requireNonNull(sourceId, "sourceId 不能为空");
        if (bytes == null || bytes.length > MAX_BYTES) {
            return failure(sourceId, ConfigurationDiagnosticCode.BYTE_LIMIT, Optional.empty());
        }
        try {
            validateDocumentBeforeMaterialization(bytes);
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                return failure(sourceId, ConfigurationDiagnosticCode.ROOT_NOT_OBJECT, Optional.empty());
            }
            validateFirstSchemaVersion(root);
            rejectUnknownFields(root, TOP_LEVEL_FIELDS, Optional.empty());
            DeclaredSettings settings = materialize(root);
            SettingsSourceSnapshot snapshot = new SettingsSourceSnapshot(
                    sourceId, new SettingsRevision(sha256(bytes)), settings, List.of());
            return new ParseResult(Optional.of(snapshot), List.of());
        } catch (ParseFailure failure) {
            return failure(sourceId, failure.code, failure.path);
        } catch (Exception ignored) {
            return failure(sourceId, ConfigurationDiagnosticCode.MALFORMED_JSON, Optional.empty());
        }
    }

    private DeclaredSettings materialize(JsonNode root) {
        Optional<String> modelName = optionalString(root, "model", "name", MODEL_FIELDS, 1, 256, SettingPath.MODEL_NAME);
        Optional<String> permissionMode = optionalEnum(root, "permission", "mode", PERMISSION_FIELDS,
                Set.of("DEFAULT", "PLAN", "ACCEPT_EDITS"), SettingPath.PERMISSION_MODE);
        List<DeclaredPermissionRule> permissionRules = permissionRules(root);
        Optional<List<String>> enabledTools = optionalStringList(root, "tools", "enabled", TOOLS_FIELDS,
                MAX_ENABLED_TOOLS, 1, 128, SettingPath.TOOLS_ENABLED);
        enabledTools.ifPresent(tools -> tools.forEach(this::requireBuiltinTool));
        Map<String, JsonObject> toolConfigurations = toolConfigurations(root);
        List<String> compactInstructions = optionalStringList(root, "context", "compactInstructions", CONTEXT_FIELDS,
                MAX_COMPACT_INSTRUCTIONS, 1, 512, SettingPath.CONTEXT_COMPACT_INSTRUCTIONS).orElse(List.of());
        Optional<String> verbosity = optionalEnum(root, "diagnostics", "verbosity", DIAGNOSTICS_FIELDS,
                Set.of("OFF", "SUMMARY", "DETAIL"), SettingPath.DIAGNOSTICS_VERBOSITY);
        return new DeclaredSettings(modelName, permissionMode, permissionRules, enabledTools,
                toolConfigurations, compactInstructions, verbosity);
    }

    private List<DeclaredPermissionRule> permissionRules(JsonNode root) {
        JsonNode permission = optionalObject(root, "permission", SettingPath.PERMISSION_RULES);
        if (permission == null) {
            return List.of();
        }
        rejectUnknownFields(permission, PERMISSION_FIELDS, Optional.of(SettingPath.PERMISSION_RULES));
        JsonNode rules = permission.get("rules");
        if (rules == null || rules.isNull()) {
            return List.of();
        }
        if (!rules.isArray()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.PERMISSION_RULES);
        }
        if (rules.size() > MAX_PERMISSION_RULES) {
            throw invalid(ConfigurationDiagnosticCode.LIST_LIMIT, SettingPath.PERMISSION_RULES);
        }
        List<DeclaredPermissionRule> parsed = new ArrayList<>();
        for (JsonNode rule : rules) {
            if (!rule.isObject()) {
                throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.PERMISSION_RULES);
            }
            if (rule.has("remove")) {
                rejectUnknownFields(rule, RULE_REMOVAL_FIELDS, Optional.of(SettingPath.PERMISSION_RULES));
                parsed.add(new DeclaredPermissionRuleRemoval(requiredRuleId(rule.get("remove"))));
                continue;
            }
            rejectUnknownFields(rule, RULE_FIELDS, Optional.of(SettingPath.PERMISSION_RULES));
            String tool = requiredString(rule.get("tool"), 1, 128, SettingPath.PERMISSION_RULES);
            requireBuiltinTool(tool);
            parsed.add(new DeclaredPermissionRuleDefinition(
                    requiredRuleId(rule.get("ruleId")),
                    requiredEnum(rule.get("decision"), Set.of("ALLOW", "ASK", "DENY"), SettingPath.PERMISSION_RULES),
                    requiredEnum(rule.get("effect"), Set.of("READ_WORKSPACE", "WRITE_WORKSPACE", "EXECUTE_PROCESS",
                            "NETWORK_OR_REMOTE", "SYSTEM_OR_DESTRUCTIVE"), SettingPath.PERMISSION_RULES),
                    tool,
                    requiredEnum(rule.get("toolSource"), Set.of("BUILT_IN"), SettingPath.PERMISSION_RULES),
                    requiredString(rule.get("selector"), 0, MAX_STRING_CODE_POINTS, SettingPath.PERMISSION_RULES)));
        }
        return List.copyOf(parsed);
    }

    private Map<String, JsonObject> toolConfigurations(JsonNode root) {
        JsonNode tools = optionalObject(root, "tools", SettingPath.TOOLS_CONFIG);
        if (tools == null) {
            return Map.of();
        }
        rejectUnknownFields(tools, TOOLS_FIELDS, Optional.of(SettingPath.TOOLS_CONFIG));
        JsonNode configurations = tools.get("config");
        if (configurations == null || configurations.isNull()) {
            return Map.of();
        }
        if (!configurations.isObject()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.TOOLS_CONFIG);
        }
        if (configurations.size() > MAX_TOOL_CONFIGS) {
            throw invalid(ConfigurationDiagnosticCode.MEMBER_LIMIT, SettingPath.TOOLS_CONFIG);
        }
        LinkedHashMap<String, JsonObject> parsed = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = configurations.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            requireBuiltinTool(entry.getKey());
            JsonNode configuration = entry.getValue();
            if (!configuration.isObject()) {
                throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.TOOLS_CONFIG);
            }
            if (configuration.size() > MAX_TOOL_CONFIG_MEMBERS) {
                throw invalid(ConfigurationDiagnosticCode.MEMBER_LIMIT, SettingPath.TOOLS_CONFIG);
            }
            Set<String> allowedFields = toolConfigurationFields.get(entry.getKey());
            LinkedHashMap<String, Object> scalarValues = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = configuration.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!allowedFields.contains(field.getKey())) {
                    throw invalid(ConfigurationDiagnosticCode.UNKNOWN_FIELD, SettingPath.TOOLS_CONFIG);
                }
                scalarValues.put(field.getKey(), scalarValue(field.getValue()));
            }
            parsed.put(entry.getKey(), new JsonObject(scalarValues));
        }
        return Collections.unmodifiableMap(parsed);
    }

    private void validateDocumentBeforeMaterialization(byte[] bytes) {
        try (tools.jackson.core.JsonParser parser = jsonFactory.createParser(bytes)) {
            tools.jackson.core.JsonToken first = parser.nextToken();
            if (first != tools.jackson.core.JsonToken.START_OBJECT) {
                throw invalid(ConfigurationDiagnosticCode.ROOT_NOT_OBJECT, null);
            }
            validateObject(parser, 1, true);
            if (parser.nextToken() != null) {
                throw invalid(ConfigurationDiagnosticCode.MALFORMED_JSON, null);
            }
        } catch (ParseFailure failure) {
            throw failure;
        } catch (Exception ignored) {
            throw invalid(ConfigurationDiagnosticCode.MALFORMED_JSON, null);
        }
    }

    private void validateObject(tools.jackson.core.JsonParser parser, int depth, boolean root) throws java.io.IOException {
        validateDepth(depth);
        int members = 0;
        boolean firstMember = true;
        Set<String> names = new HashSet<>();
        tools.jackson.core.JsonToken token;
        while ((token = parser.nextToken()) != tools.jackson.core.JsonToken.END_OBJECT) {
            if (token == null || token != tools.jackson.core.JsonToken.PROPERTY_NAME || ++members > MAX_MEMBERS) {
                throw invalid(members > MAX_MEMBERS ? ConfigurationDiagnosticCode.MEMBER_LIMIT : ConfigurationDiagnosticCode.MALFORMED_JSON, null);
            }
            String name = parser.currentName();
            if (!names.add(name)) {
                throw invalid(ConfigurationDiagnosticCode.DUPLICATE_KEY, null);
            }
            rejectForbiddenFieldName(name);
            if (root && firstMember && !"schemaVersion".equals(name)) {
                throw invalid(ConfigurationDiagnosticCode.SCHEMA_VERSION_FIRST, SettingPath.SCHEMA_VERSION);
            }
            firstMember = false;
            tools.jackson.core.JsonToken value = parser.nextToken();
            if (value == null) {
                throw invalid(ConfigurationDiagnosticCode.MALFORMED_JSON, null);
            }
            if (root && members == 1
                    && (value != tools.jackson.core.JsonToken.VALUE_NUMBER_INT || parser.getIntValue() != 1)) {
                throw invalid(ConfigurationDiagnosticCode.SCHEMA_VERSION_INVALID, SettingPath.SCHEMA_VERSION);
            }
            validateToken(parser, value, depth + 1);
        }
        if (root && firstMember) {
            throw invalid(ConfigurationDiagnosticCode.SCHEMA_VERSION_FIRST, SettingPath.SCHEMA_VERSION);
        }
    }

    private void validateArray(tools.jackson.core.JsonParser parser, int depth) throws java.io.IOException {
        validateDepth(depth);
        int items = 0;
        while (parser.nextToken() != tools.jackson.core.JsonToken.END_ARRAY) {
            if (++items > MAX_LIST_ITEMS) {
                throw invalid(ConfigurationDiagnosticCode.LIST_LIMIT, null);
            }
            validateToken(parser, parser.currentToken(), depth + 1);
        }
    }

    private void validateToken(tools.jackson.core.JsonParser parser, tools.jackson.core.JsonToken token, int depth)
            throws java.io.IOException {
        if (token == tools.jackson.core.JsonToken.START_OBJECT) {
            validateObject(parser, depth, false);
        } else if (token == tools.jackson.core.JsonToken.START_ARRAY) {
            validateArray(parser, depth);
        } else if (token == tools.jackson.core.JsonToken.VALUE_STRING
                && codePointCount(parser.getText()) > MAX_STRING_CODE_POINTS) {
            throw invalid(ConfigurationDiagnosticCode.STRING_LIMIT, null);
        }
    }

    private void validateDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid(ConfigurationDiagnosticCode.DEPTH_LIMIT, null);
        }
    }

    private Optional<String> optionalString(JsonNode root, String objectName, String fieldName, Set<String> allowedFields,
                                            int minimumPoints, int maximumPoints, SettingPath path) {
        JsonNode object = optionalObject(root, objectName, path);
        if (object == null) {
            return Optional.empty();
        }
        rejectUnknownFields(object, allowedFields, Optional.of(path));
        JsonNode value = object.get(fieldName);
        return value == null || value.isNull()
                ? Optional.empty()
                : Optional.of(requiredString(value, minimumPoints, maximumPoints, path));
    }

    private Optional<String> optionalEnum(JsonNode root, String objectName, String fieldName, Set<String> allowedFields,
                                          Set<String> allowedValues, SettingPath path) {
        Optional<String> value = optionalString(root, objectName, fieldName, allowedFields, 1, 256, path);
        if (value.isPresent() && !allowedValues.contains(value.orElseThrow())) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_VALUE, path);
        }
        return value;
    }

    private Optional<List<String>> optionalStringList(JsonNode root, String objectName, String fieldName,
                                                       Set<String> allowedFields, int maximumItems, int minimumPoints,
                                                       int maximumPoints, SettingPath path) {
        JsonNode object = optionalObject(root, objectName, path);
        if (object == null) {
            return Optional.empty();
        }
        rejectUnknownFields(object, allowedFields, Optional.of(path));
        JsonNode values = object.get(fieldName);
        if (values == null || values.isNull()) {
            return Optional.empty();
        }
        if (!values.isArray()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, path);
        }
        if (values.size() > maximumItems) {
            throw invalid(ConfigurationDiagnosticCode.LIST_LIMIT, path);
        }
        List<String> parsed = new ArrayList<>();
        for (JsonNode value : values) {
            parsed.add(requiredString(value, minimumPoints, maximumPoints, path));
        }
        return Optional.of(List.copyOf(parsed));
    }

    private JsonNode optionalObject(JsonNode root, String field, SettingPath path) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, path);
        }
        return value;
    }

    private void validateFirstSchemaVersion(JsonNode root) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        if (!fields.hasNext() || !fields.next().getKey().equals("schemaVersion")) {
            throw invalid(ConfigurationDiagnosticCode.SCHEMA_VERSION_FIRST, SettingPath.SCHEMA_VERSION);
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            throw invalid(ConfigurationDiagnosticCode.SCHEMA_VERSION_INVALID, SettingPath.SCHEMA_VERSION);
        }
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowedFields, Optional<SettingPath> path) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next().getKey())) {
                throw invalid(ConfigurationDiagnosticCode.UNKNOWN_FIELD, path.orElse(null));
            }
        }
    }

    private Object scalarValue(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.TOOLS_CONFIG);
        }
        if (node.isTextual()) {
            return requiredString(node, 0, MAX_STRING_CODE_POINTS, SettingPath.TOOLS_CONFIG);
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, SettingPath.TOOLS_CONFIG);
    }

    private String requiredRuleId(JsonNode node) {
        return requiredString(node, 1, 64, SettingPath.PERMISSION_RULES);
    }

    private String requiredEnum(JsonNode node, Set<String> allowedValues, SettingPath path) {
        String value = requiredString(node, 1, 256, path);
        if (!allowedValues.contains(value)) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_VALUE, path);
        }
        return value;
    }

    private String requiredString(JsonNode node, int minimumPoints, int maximumPoints, SettingPath path) {
        if (node == null || !node.isTextual()) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_TYPE, path);
        }
        String value = node.textValue();
        int points = codePointCount(value);
        if (points < minimumPoints || points > maximumPoints || value.indexOf('\0') >= 0) {
            throw invalid(ConfigurationDiagnosticCode.INVALID_VALUE, path);
        }
        return value;
    }

    private void requireBuiltinTool(String name) {
        if (!toolConfigurationFields.containsKey(name)) {
            throw invalid(ConfigurationDiagnosticCode.UNSUPPORTED_TOOL, null);
        }
    }

    private void rejectForbiddenFieldName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        if (normalized.contains("apikey") || normalized.contains("token") || normalized.contains("password")
                || normalized.contains("credential") || normalized.contains("secret")) {
            throw invalid(ConfigurationDiagnosticCode.FORBIDDEN_CREDENTIAL_FIELD, null);
        }
        if (normalized.contains("endpoint") || normalized.contains("baseurl")) {
            throw invalid(ConfigurationDiagnosticCode.FORBIDDEN_ENDPOINT_FIELD, null);
        }
    }

    private static Map<String, Set<String>> indexBuiltinTools(Set<String> builtinTools) {
        Objects.requireNonNull(builtinTools, "builtinTools 不能为空");
        LinkedHashMap<String, Set<String>> indexed = new LinkedHashMap<>();
        builtinTools.forEach(tool -> {
            requireRegisteredName(tool, "Tool 名");
            indexed.put(tool, Set.of());
        });
        return indexed;
    }

    private static void requireRegisteredName(String value, String label) {
        if (value == null || value.isBlank() || codePointCount(value) > 128 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " 非法");
        }
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append("%02x".formatted(value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256", impossible);
        }
    }

    private static ParseFailure invalid(ConfigurationDiagnosticCode code, SettingPath path) {
        return new ParseFailure(code, Optional.ofNullable(path));
    }

    private static ParseResult failure(SettingsSourceId sourceId, ConfigurationDiagnosticCode code,
                                       Optional<SettingPath> path) {
        return new ParseResult(Optional.empty(), List.of(new ConfigurationDiagnostic(
                sourceId, code, ConfigurationDiagnosticSeverity.ERROR, path)));
    }

    /**
     * 单来源解析的原子结果。
     *
     * @param snapshot 仅在整个来源通过校验时存在的快照
     * @param diagnostics 成功为空、失败仅有固定分类的无正文诊断
     */
    public record ParseResult(Optional<SettingsSourceSnapshot> snapshot,
                              List<ConfigurationDiagnostic> diagnostics) {
        /** 冻结结果集合，防止调用方混入后续诊断。 */
        public ParseResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics 不能为空"));
            if (snapshot.isPresent() == !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("解析结果必须且只能包含快照或失败诊断");
            }
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final ConfigurationDiagnosticCode code;
        private final Optional<SettingPath> path;

        private ParseFailure(ConfigurationDiagnosticCode code, Optional<SettingPath> path) {
            this.code = code;
            this.path = path;
        }
    }
}
