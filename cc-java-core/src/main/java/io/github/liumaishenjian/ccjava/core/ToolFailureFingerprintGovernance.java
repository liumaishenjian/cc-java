package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolFailureCategory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在单个 Run 内阻止同一失败调用被模型原样重复。
 *
 * <p>fingerprint 只由 Tool 名、类型保真且键排序的参数摘要和失败类别组成；不读取错误文案、
 * stdout/stderr、网页正文或 Secret。实例不得跨 Run 复用或持久化。</p>
 *
 * @since 0.15.0
 */
public final class ToolFailureFingerprintGovernance {
    private final Set<String> failed = new HashSet<>();

    /** 返回该调用是否已以某个稳定失败类别失败过。 */
    public synchronized boolean repeated(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        String prefix = digest(call.name(), canonical(call.arguments().values())) + ":";
        return failed.stream().anyMatch(value -> value.startsWith(prefix));
    }

    /** 记录规范失败；成功调用由调用方忽略。 */
    public synchronized void record(ToolCall call, ToolError error) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(error, "error 不能为空");
        failed.add(digest(call.name(), canonical(call.arguments().values())) + ":" + error.category().name());
    }

    /** 成功 Tool 证明策略或环境已进展，清除此前失败窗口。 */
    public synchronized void recordProgress() {
        failed.clear();
    }

    /** 构造不泄漏参数的策略反馈。 */
    public static ToolError repeatedFailure() {
        return ToolError.classified(ToolErrorCode.REPEATED_FAILURE, ToolFailureCategory.INTERNAL, false,
                "相同 Tool 调用已以同类失败结束；请改变 query/provider/source/arguments，或向用户解释阻塞原因",
                new JsonObject(Map.of("requiredStrategyChange", true,
                        "allowedChanges", List.of("query", "provider", "source", "arguments", "explanation"))));
    }

    private static String digest(String tool, String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    (tool + "|" + arguments).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> (String) entry.getKey()));
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : entries) {
                out.append(((String) entry.getKey()).length()).append(':').append(entry.getKey())
                        .append('=').append(canonical(entry.getValue())).append(';');
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) out.append(canonical(item)).append(';');
            return out.append(']').toString();
        }
        if (value instanceof String text) return "s" + text.length() + ':' + text;
        if (value instanceof Boolean bool) return "b" + bool;
        if (value instanceof Number number) return "n" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("不支持的参数类型");
    }
}
