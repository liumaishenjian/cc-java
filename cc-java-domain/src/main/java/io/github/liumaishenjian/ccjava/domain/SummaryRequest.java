package io.github.liumaishenjian.ccjava.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 交给 {@code ContextSummarizer} 的有界、不可变输入快照。
 *
 * <p>请求只携带已由 Core 选定的文本快照、来源 revision 和稳定消息 ID，不授予工具能力，
 * 也不允许摘要器读取 Canonical Transcript。候选必须回报同一 revision 与完全相同的来源 ID；
 * {@code requiredProtectedAnchors} 是必须原样保留的事实锚点。</p>
 *
 * @param tier 请求的 C3/C4 层级
 * @param inputSnapshot 已选来源的有界文本快照
 * @param sourceRevision 构建快照时的 Canonical Transcript revision
 * @param sourceMessageIds 快照覆盖的有序、唯一消息 ID
 * @param requiredProtectedAnchors 候选必须原样保留的有序、唯一事实锚点
 * @param maxOutputUtf8Bytes 候选正文允许的最大 UTF-8 字节数
 * @param maxOutputTokens 候选允许的最大估算 Token 数
 * @param sourceEstimatedTokens 被归纳来源的估算 Token 数
 * @since 0.7.0
 */
public record SummaryRequest(
        SummaryTier tier,
        String inputSnapshot,
        long sourceRevision,
        List<String> sourceMessageIds,
        List<String> requiredProtectedAnchors,
        int maxOutputUtf8Bytes,
        long maxOutputTokens,
        long sourceEstimatedTokens) {

    /** 单次摘要来源消息数量硬上限。 */
    public static final int MAX_SOURCE_MESSAGES = 512;

    /** 单次摘要输入快照 UTF-8 字节硬上限。 */
    public static final int MAX_INPUT_UTF8_BYTES = 1024 * 1024;

    /** 单次摘要受保护事实锚点数量硬上限。 */
    public static final int MAX_PROTECTED_ANCHORS = 64;

    /** 单个候选正文 UTF-8 字节硬上限。 */
    public static final int MAX_OUTPUT_UTF8_BYTES = 256 * 1024;

    /** 单个候选估算 Token 硬上限。 */
    public static final long MAX_OUTPUT_TOKENS = 1_000_000;

    /** 被归纳来源估算 Token 硬上限。 */
    public static final long MAX_SOURCE_ESTIMATED_TOKENS = 4_000_000;

    /**
     * 防御性复制集合并校验所有输入边界。
     *
     * @throws NullPointerException 必填引用为空时
     * @throws IllegalArgumentException 快照、revision、ID、锚点或预算违反边界时
     */
    public SummaryRequest {
        tier = Objects.requireNonNull(tier, "tier 不能为空");
        inputSnapshot = requireText(inputSnapshot, "inputSnapshot");
        int inputBytes = strictUtf8Length(inputSnapshot, "inputSnapshot");
        if (inputBytes > MAX_INPUT_UTF8_BYTES) {
            throw new IllegalArgumentException("inputSnapshot 超过 UTF-8 字节上限");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision 不能为负数");
        }
        sourceMessageIds = validateSourceIds(sourceMessageIds);
        requiredProtectedAnchors = validateAnchors(requiredProtectedAnchors, inputSnapshot);
        if (maxOutputUtf8Bytes < 1 || maxOutputUtf8Bytes > MAX_OUTPUT_UTF8_BYTES) {
            throw new IllegalArgumentException("maxOutputUtf8Bytes 超出允许范围");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens 超出允许范围");
        }
        if (sourceEstimatedTokens < 1
                || sourceEstimatedTokens > MAX_SOURCE_ESTIMATED_TOKENS) {
            throw new IllegalArgumentException("sourceEstimatedTokens 超出允许范围");
        }
        if (maxOutputTokens >= sourceEstimatedTokens) {
            throw new IllegalArgumentException("maxOutputTokens 必须小于来源 Token 估算");
        }
    }

    static List<String> validateSourceIds(List<String> sourceMessageIds) {
        List<String> copy = List.copyOf(
                Objects.requireNonNull(sourceMessageIds, "sourceMessageIds 不能为空"));
        if (copy.isEmpty() || copy.size() > MAX_SOURCE_MESSAGES) {
            throw new IllegalArgumentException("sourceMessageIds 数量超出允许范围");
        }
        Set<String> unique = new HashSet<>();
        for (String id : copy) {
            String checked = requireText(id, "sourceMessageId");
            strictUtf8Length(checked, "sourceMessageId");
            if (checked.codePointCount(0, checked.length()) > 128
                    || !checked.matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalArgumentException("sourceMessageId 必须是受限稳定标识");
            }
            if (!unique.add(checked)) {
                throw new IllegalArgumentException("sourceMessageIds 不能重复");
            }
        }
        return copy;
    }

    static int strictUtf8Length(String value, String field) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(field + " 必须能严格编码为 UTF-8", invalidUnicode);
        }
    }

    private static List<String> validateAnchors(
            List<String> requiredProtectedAnchors,
            String inputSnapshot) {
        List<String> copy = List.copyOf(Objects.requireNonNull(
                requiredProtectedAnchors, "requiredProtectedAnchors 不能为空"));
        if (copy.size() > MAX_PROTECTED_ANCHORS) {
            throw new IllegalArgumentException("requiredProtectedAnchors 数量超出允许范围");
        }
        Set<String> unique = new HashSet<>();
        for (String anchor : copy) {
            String checked = requireText(anchor, "protectedAnchor");
            strictUtf8Length(checked, "protectedAnchor");
            if (checked.codePointCount(0, checked.length()) > 512
                    || checked.chars().anyMatch(character -> character == '\r'
                            || character == '\n'
                            || Character.isISOControl(character))) {
                throw new IllegalArgumentException("protectedAnchor 必须是有界单行文本");
            }
            if (!unique.add(checked)) {
                throw new IllegalArgumentException("requiredProtectedAnchors 不能重复");
            }
            if (!inputSnapshot.contains(checked)) {
                throw new IllegalArgumentException("protectedAnchor 必须来自 inputSnapshot");
            }
        }
        return copy;
    }

    private static String requireText(String value, String field) {
        String checked = Objects.requireNonNull(value, field + " 不能为空");
        if (checked.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return checked;
    }
}
