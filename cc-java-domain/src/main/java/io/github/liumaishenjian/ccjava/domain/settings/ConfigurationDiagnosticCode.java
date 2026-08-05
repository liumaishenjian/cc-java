package io.github.liumaishenjian.ccjava.domain.settings;

/** 严格 Settings v1 解析器可公开的固定失败分类。 @since 0.8.0 */
public enum ConfigurationDiagnosticCode {
    /** JSON 语法无效或有尾随 token。 */ MALFORMED_JSON,
    /** 字节数量超限。 */ BYTE_LIMIT,
    /** 嵌套深度超限。 */ DEPTH_LIMIT,
    /** Object 成员数量超限。 */ MEMBER_LIMIT,
    /** Array 元素数量超限。 */ LIST_LIMIT,
    /** 字符串长度超限。 */ STRING_LIMIT,
    /** 根节点不是 Object。 */ ROOT_NOT_OBJECT,
    /** schemaVersion 不是第一个成员。 */ SCHEMA_VERSION_FIRST,
    /** schemaVersion 不支持。 */ SCHEMA_VERSION_INVALID,
    /** 同一 Object 出现重复成员名。 */ DUPLICATE_KEY,
    /** 出现未声明字段。 */ UNKNOWN_FIELD,
    /** 字段 JSON 类型错误。 */ INVALID_TYPE,
    /** 标量值不符合约束。 */ INVALID_VALUE,
    /** 引用了未注册 Tool。 */ UNSUPPORTED_TOOL,
    /** 权限规则不受支持。 */ UNSUPPORTED_RULE,
    /** 出现凭证类字段。 */ FORBIDDEN_CREDENTIAL_FIELD,
    /** 出现端点类字段。 */ FORBIDDEN_ENDPOINT_FIELD
}
