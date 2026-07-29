package io.github.liumaishenjian.ccjava.tools.local.tool;

import java.util.Objects;
import java.util.regex.Pattern;

/** 对 `/` 分隔协议路径执行确定性 glob 匹配。 */
final class ProtocolGlob {

    private final Pattern pattern;

    private ProtocolGlob(Pattern pattern) {
        this.pattern = pattern;
    }

    static ProtocolGlob compile(String glob) {
        Objects.requireNonNull(glob, "glob 不能为空");
        if (glob.isBlank() || glob.length() > 1024) {
            throw new IllegalArgumentException("glob 为空或超过长度上限");
        }
        String normalized = glob.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < normalized.length()
                        && normalized.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if (".()[]{}+$^|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        regex.append('$');
        return new ProtocolGlob(Pattern.compile(regex.toString()));
    }

    boolean matches(String protocolPath) {
        return pattern.matcher(protocolPath.replace('\\', '/')).matches();
    }
}
