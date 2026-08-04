package io.github.liumaishenjian.ccjava.tools.local.memory;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 对写入 M1 的候选文本执行保守、无回显的明显 Secret 检测。
 *
 * <p>该策略不是凭证分类器，也不记录匹配文本；任何命中只返回布尔拒绝。模型生成的文本不会因
 * 未命中而自动成为可信事实，调用方仍须遵守产品确认和 Permission/Session 边界。</p>
 *
 * @since 0.7.0
 */
public final class SecretCandidatePolicy {

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?im)\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|credential|client[_-]?secret)\\b\\s*[:=]\\s*['\\\"]?\\S+");
    private static final Pattern BEARER = Pattern.compile(
            "(?im)\\bauthorization\\s*[:=]\\s*bearer\\s+\\S+|\\bbearer\\s+[A-Za-z0-9._~+/-]{4,}");
    private static final Pattern ENDPOINT = Pattern.compile(
            "(?im)\\b(?:provider[_-]?(?:endpoint|url)|api[_-]?(?:endpoint|url)|base[_-]?url|endpoint)\\b\\s*[:=]\\s*https?://\\S+");

    /**
     * 判断候选是否包含明显 Secret、Private Key header 或 Provider endpoint 赋值。
     *
     * @param candidate frontmatter 与正文的完整候选文本
     * @return 命中保守规则时为 {@code true}
     */
    public boolean isSecretCandidate(String candidate) {
        String value = Objects.requireNonNull(candidate, "candidate 不能为空");
        String lower = value.toLowerCase(Locale.ROOT);
        return ASSIGNMENT.matcher(value).find()
                || BEARER.matcher(value).find()
                || ENDPOINT.matcher(value).find()
                || lower.contains("-----begin private key-----")
                || lower.contains("-----begin rsa private key-----")
                || lower.contains("-----begin ec private key-----")
                || lower.contains("-----begin openssh private key-----");
    }
}
