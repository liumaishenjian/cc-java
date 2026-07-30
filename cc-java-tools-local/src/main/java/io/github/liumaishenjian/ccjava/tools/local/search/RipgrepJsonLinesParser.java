package io.github.liumaishenjian.ccjava.tools.local.search;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 有界解析 ripgrep {@code --json} 输出。
 *
 * <p>解析器只接受每行一个 JSON 对象。未知事件会被忽略；已知事件缺少必需字段时也会被过滤；
 * 语法错误、重复字段和资源上限违规会使整次解析失败。这样既允许未来 ripgrep 添加事件，又不会把
 * 损坏或歧义输入误当成可信搜索结果。</p>
 *
 * @since 0.3.1
 */
public final class RipgrepJsonLinesParser {

    /** 默认单条 JSON 事件最大 UTF-8 字节数。 */
    public static final int DEFAULT_MAX_LINE_BYTES = 1024 * 1024;
    /** 默认全部 JSON Lines 最大 UTF-8 字节数。 */
    public static final int DEFAULT_MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    /** 默认最大事件数。 */
    public static final int DEFAULT_MAX_EVENTS = 10_000;

    private final int maxLineBytes;
    private final int maxTotalBytes;
    private final int maxEvents;
    private final ObjectMapper mapper;

    /** 使用与搜索进程输出上限相匹配的默认边界。 */
    public RipgrepJsonLinesParser() {
        this(DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_EVENTS);
    }

    /**
     * 创建可在测试和不同执行环境中调整上限的解析器。
     *
     * @param maxLineBytes 单条事件最大 UTF-8 字节数
     * @param maxTotalBytes 全部事件最大 UTF-8 字节数
     * @param maxEvents 最大事件数
     */
    public RipgrepJsonLinesParser(int maxLineBytes, int maxTotalBytes, int maxEvents) {
        if (maxLineBytes < 1 || maxTotalBytes < maxLineBytes || maxEvents < 1) {
            throw new IllegalArgumentException("ripgrep JSON 解析上限非法");
        }
        this.maxLineBytes = maxLineBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxEvents = maxEvents;
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * 解析完整 JSON Lines 序列并建立三种输出模式需要的内部视图。
     *
     * @param lines 不含换行分隔符的 JSON 行
     * @return 不可变聚合结果
     * @throws RipgrepJsonParseException JSON 语法无效、字段歧义或输入超限
     */
    public RipgrepParsedResult parse(List<String> lines) throws RipgrepJsonParseException {
        if (lines == null) {
            throw new RipgrepJsonParseException("ripgrep JSON 输入不能为空");
        }
        if (lines.size() > maxEvents) {
            throw new RipgrepJsonParseException("ripgrep JSON 事件数量超过上限");
        }

        List<RipgrepJsonEvent.SearchLine> content = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        RipgrepJsonEvent.Summary summary = null;
        int ignored = 0;
        long totalBytes = 0;

        for (String line : lines) {
            if (line == null) {
                ignored++;
                continue;
            }
            int bytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxLineBytes) {
                throw new RipgrepJsonParseException("单条 ripgrep JSON 事件超过上限");
            }
            totalBytes += bytes + 1L;
            if (totalBytes > maxTotalBytes) {
                throw new RipgrepJsonParseException("ripgrep JSON 总输入超过上限");
            }
            if (line.isBlank()) {
                ignored++;
                continue;
            }

            JsonNode root;
            try {
                root = mapper.readTree(line);
            } catch (Exception exception) {
                throw new RipgrepJsonParseException(
                        exception.getMessage() != null
                                        && exception.getMessage().toLowerCase().contains("duplicate")
                                ? "ripgrep JSON 包含重复字段"
                                : "ripgrep JSON 语法无效");
            }
            if (root == null || !root.isObject()) {
                throw new RipgrepJsonParseException("ripgrep JSON 顶层必须是对象");
            }
            RipgrepJsonEvent event = toEvent(root);
            if (event == null) {
                ignored++;
                continue;
            }
            if (event instanceof RipgrepJsonEvent.SearchLine searchLine) {
                content.add(searchLine);
                if (searchLine.kind() == RipgrepJsonEvent.LineKind.MATCH) {
                    files.add(searchLine.path());
                    counts.merge(searchLine.path(), 1L, Long::sum);
                }
            } else if (event instanceof RipgrepJsonEvent.Summary parsedSummary) {
                summary = parsedSummary;
            }
        }
        return new RipgrepParsedResult(
                content, new ArrayList<>(files), counts, summary, ignored);
    }

    private RipgrepJsonEvent toEvent(JsonNode root) {
        String type = string(root.get("type"));
        JsonNode data = object(root.get("data"));
        if (type == null || data == null) {
            return null;
        }
        return switch (type) {
            case "begin" -> boundary(data, RipgrepJsonEvent.BoundaryKind.BEGIN);
            case "end" -> boundary(data, RipgrepJsonEvent.BoundaryKind.END);
            case "match" -> searchLine(data, RipgrepJsonEvent.LineKind.MATCH);
            case "context" -> searchLine(data, RipgrepJsonEvent.LineKind.CONTEXT);
            case "summary" -> summary(data);
            default -> null;
        };
    }

    private RipgrepJsonEvent.FileBoundary boundary(
            JsonNode data, RipgrepJsonEvent.BoundaryKind kind) {
        String path = textValue(data.get("path"));
        return path == null || path.isBlank() ? null : new RipgrepJsonEvent.FileBoundary(kind, path);
    }

    private RipgrepJsonEvent.SearchLine searchLine(
            JsonNode data, RipgrepJsonEvent.LineKind kind) {
        String path = textValue(data.get("path"));
        String text = textValue(data.get("lines"));
        Long lineNumber = integer(data.get("line_number"));
        Long absoluteOffset = integer(data.get("absolute_offset"));
        if (path == null
                || path.isBlank()
                || text == null
                || lineNumber == null
                || lineNumber < 0
                || absoluteOffset == null
                || absoluteOffset < 0) {
            return null;
        }

        List<RipgrepJsonEvent.Submatch> submatches = new ArrayList<>();
        JsonNode rawSubmatches = data.get("submatches");
        if (rawSubmatches != null && rawSubmatches.isArray()) {
            for (JsonNode raw : rawSubmatches) {
                if (raw == null || !raw.isObject()) {
                    return null;
                }
                Long start = integer(raw.get("start"));
                Long end = integer(raw.get("end"));
                String matchedText = textValue(raw.get("match"));
                if (start == null
                        || end == null
                        || start < 0
                        || end < start
                        || matchedText == null) {
                    return null;
                }
                submatches.add(new RipgrepJsonEvent.Submatch(start, end, matchedText));
            }
        } else if (kind == RipgrepJsonEvent.LineKind.MATCH) {
            return null;
        }
        return new RipgrepJsonEvent.SearchLine(
                kind, path, lineNumber, absoluteOffset, text, submatches);
    }

    private RipgrepJsonEvent.Summary summary(JsonNode data) {
        JsonNode stats = object(data.get("stats"));
        if (stats == null) {
            return null;
        }
        long matchedLines = statistic(stats.get("matched_lines"));
        long matches = statistic(stats.get("matches"));
        return matchedLines < 0 && matches < 0
                ? null
                : new RipgrepJsonEvent.Summary(matchedLines, matches);
    }

    private long statistic(JsonNode raw) {
        Long value = integer(raw);
        return value == null || value < 0 ? -1 : value;
    }

    private String textValue(JsonNode raw) {
        JsonNode value = object(raw);
        if (value == null) {
            return null;
        }
        // bytes 是 Base64 编码的非 UTF-8 数据。源码搜索默认拒绝二进制内容，避免有损解码。
        return string(value.get("text"));
    }

    private JsonNode object(JsonNode value) {
        return value != null && value.isObject() ? value : null;
    }

    private String string(JsonNode value) {
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private Long integer(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? value.longValue()
                : null;
    }
}
