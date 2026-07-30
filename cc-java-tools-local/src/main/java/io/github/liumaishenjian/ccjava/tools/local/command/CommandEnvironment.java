package io.github.liumaishenjian.ccjava.tools.local.command;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 为 S04 命令子进程构造固定最小环境。
 *
 * <p>实现采用 allowlist，而不是继承父进程全部环境，从而不会把 Provider Key、
 * 自定义 Header 或未知 Secret 自动暴露给模型启动的 Shell。保留项只覆盖平台启动、
 * PATH、临时目录以及 Java/Maven/npm 常用的本地缓存定位。</p>
 *
 * @since 0.4.0
 */
final class CommandEnvironment {

    private static final Set<String> ALLOWED = Set.of(
            "path", "pathext", "systemroot", "windir", "comspec",
            "temp", "tmp", "tmpdir", "home", "userprofile",
            "appdata", "localappdata", "programdata", "programfiles",
            "programfiles(x86)", "commonprogramfiles",
            "java_home", "maven_home", "m2_home", "gradle_user_home",
            "npm_config_cache", "lang", "lc_all", "term");

    private CommandEnvironment() {
    }

    /**
     * 从父进程环境复制固定 allowlist。
     *
     * @return 不含 Provider 凭证的子进程环境
     */
    static Map<String, String> minimal() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        System.getenv().forEach((name, value) -> {
            if (ALLOWED.contains(name.toLowerCase(Locale.ROOT))) {
                result.put(name, value);
            }
        });
        result.put("NO_COLOR", "1");
        result.put("CLICOLOR", "0");
        return Map.copyOf(result);
    }
}
