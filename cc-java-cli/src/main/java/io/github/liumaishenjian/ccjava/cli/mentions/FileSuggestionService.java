package io.github.liumaishenjian.ccjava.cli.mentions;

import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 为显式文件提及提供有界、只读且非权威的 Workspace-relative 候选。
 *
 * <p>候选只改善 UX；提交仍必须由 {@link FileMentionService} 重新验证。扫描不跟随链接，
 * 每个候选再次经过 WorkspaceGuard，因此敏感路径和逃逸目标不会进入结果。</p>
 *
 * @since 0.8.1
 */
public final class FileSuggestionService {

    /** 最多返回候选数。 */
    public static final int MAX_CANDIDATES = 32;
    private static final int MAX_SCANNED = 10_000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_CANDIDATE_CODE_POINTS = 1_024;

    private final WorkspaceGuard guard;

    /**
     * 绑定当前 Headless Session 固定的 Workspace 边界。
     *
     * @param guard 启动时固定真实 Workspace 的权威 Guard
     */
    public FileSuggestionService(WorkspaceGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
    }

    /**
     * 返回 prefix 优先、contains 次之、协议路径 UTF-8 bytewise 排序的候选。
     *
     * @param query 最多 256 code point 的相对路径查询
     * @return 最多 32 项不可变列表
     */
    public List<String> suggest(String query) {
        Objects.requireNonNull(query, "query 不能为空");
        if (query.codePointCount(0, query.length()) > 256 || query.indexOf('\0') >= 0
                || query.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("FILE_SUGGEST_INVALID_QUERY");
        }
        String needle = query.toLowerCase(Locale.ROOT).replace('\\', '/');
        List<String> matches = new ArrayList<>();
        try (var stream = Files.walk(guard.workspace(), MAX_DEPTH)) {
            stream.limit(MAX_SCANNED)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> addSafe(matches, path, needle));
        } catch (IOException failure) {
            throw new IllegalStateException("FILE_SUGGEST_SCAN_FAILED");
        }
        matches.sort(Comparator
                .comparingInt((String value) -> rank(value.toLowerCase(Locale.ROOT), needle))
                .thenComparing(FileSuggestionService::compareUtf8));
        return List.copyOf(matches.subList(0, Math.min(matches.size(), MAX_CANDIDATES)));
    }

    private void addSafe(List<String> matches, Path path, String needle) {
        try {
            String logical = guard.workspace().relativize(path).toString().replace('\\', '/');
            String checked = guard.requireRegularFile(logical).protocolPath();
            if (checked.codePointCount(0, checked.length()) <= MAX_CANDIDATE_CODE_POINTS
                    && checked.indexOf('"') < 0
                    && checked.chars().noneMatch(Character::isISOControl)
                    && checked.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(checked);
            }
        } catch (io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceAccessException
                | RuntimeException ignored) {
            // 单个候选不能通过权威 Guard 时忽略，绝不放宽扫描。
        }
    }

    private static int rank(String value, String needle) {
        return value.startsWith(needle) ? 0 : 1;
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int index = 0; index < limit; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }
}
