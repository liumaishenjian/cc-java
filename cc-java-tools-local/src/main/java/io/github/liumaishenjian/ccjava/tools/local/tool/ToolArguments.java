package io.github.liumaishenjian.ccjava.tools.local.tool;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** S03 内置工具共用的严格参数读取辅助。 */
final class ToolArguments {

    private ToolArguments() {
    }

    static String string(JsonObject arguments, String name, String defaultValue) {
        Object value = arguments.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(name + " 必须是字符串");
        }
        return string;
    }

    static int integer(JsonObject arguments, String name, int defaultValue) {
        Object value = arguments.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || !isIntegral(number)) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        long longValue = number.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " 超出整数范围");
        }
        return (int) longValue;
    }

    static boolean bool(JsonObject arguments, String name, boolean defaultValue) {
        Object value = arguments.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(name + " 必须是布尔值");
        }
        return bool;
    }

    static void rejectUnknown(JsonObject arguments, Set<String> allowed) {
        for (String key : arguments.values().keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("未知参数: " + key);
            }
        }
    }

    static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    static void requireMaximumCharacters(String name, String value, int maximum) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(name + " 超过字符上限 " + maximum);
        }
    }

    static void rejectBinaryNull(String name, String value) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " 不能包含二进制 NUL");
        }
    }

    static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "%s 必须在 %d 到 %d 之间".formatted(name, minimum, maximum));
        }
    }

    private static boolean isIntegral(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long
                || number instanceof BigInteger) {
            return true;
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value);
    }
}
