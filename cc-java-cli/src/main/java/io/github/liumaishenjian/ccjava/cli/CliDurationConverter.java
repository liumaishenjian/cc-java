package io.github.liumaishenjian.ccjava.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 把 CLI 墙钟限制解析为 {@link Duration}。
 *
 * <p>接受整数加 {@code ms}/{@code s}/{@code m}，以及 ISO-8601 Duration。
 * 范围限制由 {@link CliOverrides} 统一校验。</p>
 *
 * @since 0.1.0
 */
final class CliDurationConverter implements ITypeConverter<Duration> {

    @Override
    public Duration convert(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(parseNumber(normalized, 2));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(parseNumber(normalized, 1));
            }
            if (normalized.endsWith("m")) {
                return Duration.ofMinutes(parseNumber(normalized, 1));
            }
            return Duration.parse(value.trim().toUpperCase(Locale.ROOT));
        } catch (ArithmeticException
                 | DateTimeParseException
                 | NumberFormatException exception) {
            throw invalid();
        }
    }

    private static long parseNumber(String value, int suffixLength) {
        return Long.parseLong(value.substring(0, value.length() - suffixLength));
    }

    private static TypeConversionException invalid() {
        return new TypeConversionException(
                "timeout must use 250ms, 30s, 5m, or ISO-8601 such as PT30S");
    }
}
