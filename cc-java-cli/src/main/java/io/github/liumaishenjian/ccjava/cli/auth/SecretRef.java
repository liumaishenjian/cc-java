package io.github.liumaishenjian.ccjava.cli.auth;

import java.util.Objects;

/**
 * Credential profile 中不含秘密值的引用。
 *
 * <p>STORE identity 和 ENV 变量名只能由 resolver 使用，不得进入 JSON/stdio list 输出。</p>
 *
 * @since 0.1.0
 */
public sealed interface SecretRef permits SecretRef.Store, SecretRef.Env {
    /** Secret 来源类别。 */
    enum Kind {
        /** 权限受限的本地凭证存储。 */
        STORE,
        /** 进程环境变量。 */
        ENV
    }

    /**
     * 返回来源类别。
     *
     * @return 此引用的 Secret 来源类别
     */
    Kind kind();

    /**
     * 权限受限文件 store 的 opaque identity。
     *
     * @param secretId 由 32 位小写十六进制字符组成的秘密标识
     */
    record Store(String secretId) implements SecretRef {
        /** 校验 128-bit lowercase hex identity。 */
        public Store {
            Objects.requireNonNull(secretId, "secretId 不能为空");
            if (!secretId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("secretId 格式无效");
        }
        @Override public Kind kind() { return Kind.STORE; }
        @Override public String toString() { return "SecretRef.Store[<redacted>]"; }
    }

    /**
     * 每次 lease 获取时读取的环境变量引用。
     *
     * @param variableName 保存秘密值的环境变量名
     */
    record Env(String variableName) implements SecretRef {
        /** 校验显式变量 identity。 */
        public Env {
            Objects.requireNonNull(variableName, "variableName 不能为空");
            if (!variableName.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("ENV identity 无效");
        }
        @Override public Kind kind() { return Kind.ENV; }
        @Override public String toString() { return "SecretRef.Env[<redacted>]"; }
    }
}
