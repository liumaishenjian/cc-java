package io.github.liumaishenjian.ccjava.domain.settings;

/** Settings 字段合并时的已声明操作。 @since 0.8.0 */
public enum SettingOperation {
    /** 设置标量。 */ SET,
    /** 整体替换对象。 */ REPLACE,
    /** 追加有序元素。 */ APPEND,
    /** 删除已有元素。 */ REMOVE,
    /** 不产生有效变更。 */ NO_OP
}
