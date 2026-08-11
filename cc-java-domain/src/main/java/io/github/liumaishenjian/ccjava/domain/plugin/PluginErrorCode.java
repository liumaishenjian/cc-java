package io.github.liumaishenjian.ccjava.domain.plugin;

/**
 * Plugin 边界向 Core/CLI 暴露的隐私安全结构化错误码。
 *
 * @since 0.11.0
 */
public enum PluginErrorCode {
    /** Manifest 超过固定字节上限。 */
    MANIFEST_TOO_LARGE,
    /** Manifest schema、类型、编码或字段非法。 */
    MANIFEST_INVALID,
    /** Manifest 声明的组件数超过上限。 */
    COMPONENT_LIMIT_EXCEEDED,
    /** Package 输入不是 ordinary directory。 */
    PACKAGE_NOT_DIRECTORY,
    /** 输入是当前契约明确不支持的 archive。 */
    ARCHIVE_REJECTED,
    /** 路径越界、身份不一致或不可安全解析。 */
    PATH_REJECTED,
    /** Tree 中出现链接、reparse point 或特殊文件。 */
    LINK_OR_SPECIAL_FILE_REJECTED,
    /** Canonical tree 文件数超过上限。 */
    TREE_FILE_LIMIT_EXCEEDED,
    /** Canonical tree 总字节数超过上限。 */
    TREE_SIZE_LIMIT_EXCEEDED,
    /** 两次身份检查之间内容发生变化。 */
    CONTENT_CHANGED,
    /** Snapshot fingerprint 未通过宿主 trust Gate。 */
    FINGERPRINT_UNTRUSTED,
    /** Tool Provider 类型、配置摘要或资源创建被拒绝。 */
    PROVIDER_REJECTED,
    /** Plugin 安装事务未能安全发布。 */
    INSTALL_FAILED,
    /** 卸载因活动 lease 延期，未执行物理删除。 */
    UNINSTALL_DEFERRED,
    /** 物理删除失败并保留 tombstone。 */
    UNINSTALL_TOMBSTONED
}
