package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.ToolCall;
import io.github.liumaishenjian.ccjava.domain.ToolError;
import io.github.liumaishenjian.ccjava.domain.ToolErrorCode;
import io.github.liumaishenjian.ccjava.domain.ToolFailureCategory;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
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
 * <p>失败记录只由 Tool 名、类型保真且键排序的参数摘要和失败类别组成；不读取错误文案、
 * stdout/stderr、网页正文或 Secret。由于执行前无法预知重试将产生的类别，Pre Gate 以
 * Tool 名和参数匹配任一既有类型化失败记录；类别用于限制后续成功调用可证明恢复的范围。
 * 实例不得跨 Run 复用或持久化。</p>
 *
 * @since 0.15.0
 */
public final class ToolFailureFingerprintGovernance {
    private final Set<FailureFingerprint> failed = new HashSet<>();

    /** 返回该 Tool 与规范参数是否已有任一类型化失败记录；执行前不猜测下一次失败类别。 */
    public synchronized boolean repeated(ToolCall call) {
        Objects.requireNonNull(call, "call 不能为空");
        String arguments = argumentsDigest(call);
        return failed.stream().anyMatch(value ->
                value.tool().equals(call.name()) && value.arguments().equals(arguments));
    }

    /** 记录由 Tool、规范参数与类型化失败类别共同组成的 fingerprint。 */
    public synchronized void record(ToolCall call, ToolError error) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(error, "error 不能为空");
        failed.add(new FailureFingerprint(call.name(), argumentsDigest(call), error.category()));
    }

    /**
     * 记录真实成功，并按可证明的恢复范围清理失败窗口。
     *
     * <p>同一 Tool 变参成功证明其策略已经改变，因此清除该 Tool 的旧 fingerprint；成功写入
     * Workspace 或改变系统状态只会释放可能由本地内容导致的进程失败。纯读取、PlanArtifact
     * 写入、用户交互以及跨 Tool 的 HTTP/Permission 失败都没有得到恢复证明，仍须拦截。</p>
     *
     * @param call 已由 Adapter 真实执行成功的调用
     * @param effect Tool 声明的最高副作用等级
     */
    public synchronized void recordSuccess(ToolCall call, ToolEffect effect) {
        Objects.requireNonNull(call, "call 不能为空");
        Objects.requireNonNull(effect, "effect 不能为空");
        failed.removeIf(value -> value.tool().equals(call.name())
                || recoversCrossToolFailure(effect, value.category()));
    }

    private static boolean recoversCrossToolFailure(ToolEffect effect, ToolFailureCategory category) {
        return category == ToolFailureCategory.PROCESS_EXIT
                && (effect == ToolEffect.WRITE_WORKSPACE
                        || effect == ToolEffect.SYSTEM_OR_DESTRUCTIVE);
    }

    /** 构造不泄漏参数的策略反馈。 */
    public static ToolError repeatedFailure() {
        return ToolError.classified(ToolErrorCode.REPEATED_FAILURE, ToolFailureCategory.INTERNAL, false,
                "相同 Tool 调用已以同类失败结束；请改变 query/provider/source/arguments，或向用户解释阻塞原因",
                new JsonObject(Map.of("requiredStrategyChange", true,
                        "allowedChanges", List.of("query", "provider", "source", "arguments", "explanation"))));
    }

    private static String argumentsDigest(ToolCall call) {
        return digest(canonical(call.arguments().values()));
    }

    private static String digest(String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    arguments.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private record FailureFingerprint(
            String tool,
            String arguments,
            ToolFailureCategory category) {
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
