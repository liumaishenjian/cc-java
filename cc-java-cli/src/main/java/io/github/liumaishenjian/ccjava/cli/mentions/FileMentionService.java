package io.github.liumaishenjian.ccjava.cli.mentions;

import io.github.liumaishenjian.ccjava.domain.UserFileAttachment;
import io.github.liumaishenjian.ccjava.domain.UserMessage;
import io.github.liumaishenjian.ccjava.tools.local.workspace.ValidatedWorkspacePath;
import io.github.liumaishenjian.ccjava.tools.local.workspace.WorkspaceGuard;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 把用户正文中的显式 {@code @file} 令牌解析为受 WorkspaceGuard 约束的不可变快照。
 *
 * <p>解析只认可位于输入起始或空白之后的未引号/双引号令牌，并支持 {@code #Lstart} 与
 * {@code #Lstart-end}。任何显式令牌失败都会在 Runtime/Session 变更前以固定 code 整体拒绝；
 * 正文不被改写，附件内容仍是不可信模型上下文。</p>
 *
 * @since 0.8.1
 */
public final class FileMentionService {

    /** 单消息最多显式文件数。 */
    public static final int MAX_MENTIONS = 8;
    /** 单附件最多选择行数。 */
    public static final int MAX_SELECTED_LINES = 500;
    /** 单附件 UTF-8 上限。 */
    public static final int MAX_ATTACHMENT_BYTES = 65_536;
    /** 单消息附件 UTF-8 总上限。 */
    public static final int MAX_TOTAL_BYTES = 196_608;
    /**
     * 单个被提及源文件的读取上限。
     *
     * <p>该上限只约束一次性读入内存的字节数，与附件快照上限相互独立：它允许从较大文件中选择
     * 有界行区间，同时避免把任意大小的 Workspace 文件读进进程。这是 cc-java 的独立保守预算。</p>
     */
    public static final int MAX_SOURCE_FILE_BYTES = 1_048_576;

    private final WorkspaceGuard guard;

    /**
     * 绑定与 Headless Runtime 完全相同的 Workspace 边界。
     *
     * @param guard 启动时固定真实 Workspace 的权威 Guard
     */
    public FileMentionService(WorkspaceGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard 不能为空");
    }

    /**
     * 解析正文并读取全部有效快照。
     *
     * @param text 原始用户正文
     * @return 保留原文且附件按首次出现稳定排序的消息
     * @throws FileMentionException 任一显式提及非法或超限时
     */
    public UserMessage resolve(String text) {
        Objects.requireNonNull(text, "text 不能为空");
        List<Mention> mentions = List.copyOf(new LinkedHashSet<>(parse(text)));
        if (mentions.size() > MAX_MENTIONS) {
            throw new FileMentionException();
        }
        List<UserFileAttachment> attachments = new ArrayList<>();
        LinkedHashSet<AttachmentKey> resolved = new LinkedHashSet<>();
        int total = 0;
        for (Mention mention : mentions) {
            UserFileAttachment attachment = read(mention);
            AttachmentKey key = new AttachmentKey(
                    attachment.protocolPath(), mention.startLine, mention.endLine);
            if (!resolved.add(key)) {
                continue;
            }
            total = Math.addExact(total, attachment.textSnapshot().getBytes(StandardCharsets.UTF_8).length);
            if (total > MAX_TOTAL_BYTES) {
                throw new FileMentionException();
            }
            attachments.add(attachment);
        }
        return new UserMessage(text, attachments);
    }

    private List<Mention> parse(String text) {
        List<Mention> result = new ArrayList<>();
        for (int index = 0; index < text.length();) {
            int marker = text.indexOf('@', index);
            if (marker < 0) break;
            if (marker > 0 && !Character.isWhitespace(text.charAt(marker - 1))) {
                index = marker + 1;
                continue;
            }
            int cursor = marker + 1;
            String path;
            if (cursor < text.length() && text.charAt(cursor) == '"') {
                int close = text.indexOf('"', cursor + 1);
                if (close < 0) throw new FileMentionException();
                path = text.substring(cursor + 1, close);
                cursor = close + 1;
                if (cursor < text.length() && !text.startsWith("#L", cursor)
                        && !Character.isWhitespace(text.charAt(cursor))) {
                    throw new FileMentionException();
                }
            } else {
                int end = cursor;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
                String token = text.substring(cursor, end);
                int hash = token.lastIndexOf("#L");
                path = hash >= 0 ? token.substring(0, hash) : token;
                cursor = hash >= 0 ? cursor + hash : end;
            }
            if (path.isBlank()) throw new FileMentionException();
            LineRange range = parseRange(text, cursor);
            result.add(new Mention(path, range.start, range.end));
            index = Math.max(range.nextIndex, marker + 1);
        }
        return result;
    }

    private LineRange parseRange(String text, int cursor) {
        if (cursor + 2 > text.length() || !text.startsWith("#L", cursor)) {
            return new LineRange(1, Integer.MAX_VALUE, cursor);
        }
        int pos = cursor + 2;
        int startPos = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (pos == startPos) throw new FileMentionException();
        int start = positiveInt(text.substring(startPos, pos));
        int end = start;
        if (pos < text.length() && text.charAt(pos) == '-') {
            int endPos = ++pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (pos == endPos) throw new FileMentionException();
            end = positiveInt(text.substring(endPos, pos));
        }
        if (end < start) throw new FileMentionException();
        if (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) throw new FileMentionException();
        return new LineRange(start, end, pos);
    }

    private int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new FileMentionException();
        }
    }

    private UserFileAttachment read(Mention mention) {
        try {
            ValidatedWorkspacePath before = guard.requireRegularFile(mention.path);
            BasicFileAttributes initial = Files.readAttributes(
                    before.realPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (initial.size() > MAX_SOURCE_FILE_BYTES) {
                throw new FileMentionException();
            }
            byte[] bytes = readBounded(before.realPath());
            String text = strictUtf8(bytes);
            ValidatedWorkspacePath after = guard.requireRegularFile(mention.path);
            BasicFileAttributes current = Files.readAttributes(
                    after.realPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameIdentity(before, initial, after, current)) {
                throw new FileMentionException();
            }
            // 某些文件系统允许在粗粒度 mtime 内同长改写。再做一次有界读取并比较原始字节，
            // 防止把一次并发改写前后的片段拼成并不存在的混合快照。
            byte[] verified = readBounded(after.realPath());
            ValidatedWorkspacePath verifiedPath = guard.requireRegularFile(mention.path);
            BasicFileAttributes verifiedAttributes = Files.readAttributes(
                    verifiedPath.realPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameIdentity(after, current, verifiedPath, verifiedAttributes)
                    || !Arrays.equals(bytes, verified)) {
                throw new FileMentionException();
            }
            return select(verifiedPath.protocolPath(), text, mention);
        } catch (FileMentionException known) {
            throw known;
        } catch (Exception failure) {
            throw new FileMentionException();
        }
    }

    private byte[] readBounded(java.nio.file.Path path) throws java.io.IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_FILE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_FILE_BYTES) {
                throw new FileMentionException();
            }
            return bytes;
        }
    }

    private boolean sameIdentity(
            ValidatedWorkspacePath before,
            BasicFileAttributes initial,
            ValidatedWorkspacePath after,
            BasicFileAttributes current) {
        return before.realPath().equals(after.realPath())
                && Objects.equals(initial.fileKey(), current.fileKey())
                && initial.size() == current.size()
                && initial.lastModifiedTime().equals(current.lastModifiedTime());
    }

    private UserFileAttachment select(String protocolPath, String text, Mention mention) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || mention.startLine > lines.size()) throw new FileMentionException();
        int requestedEnd = mention.endLine == Integer.MAX_VALUE ? lines.size() : mention.endLine;
        int actualEnd = Math.min(requestedEnd, lines.size());
        int boundedEnd = Math.min(actualEnd, mention.startLine + MAX_SELECTED_LINES - 1);
        boolean truncated = boundedEnd < requestedEnd;
        StringBuilder selected = new StringBuilder();
        int end = mention.startLine - 1;
        for (int line = mention.startLine; line <= boundedEnd; line++) {
            String candidate = selected.isEmpty() ? lines.get(line - 1) : selected + "\n" + lines.get(line - 1);
            if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_ATTACHMENT_BYTES) {
                truncated = true;
                break;
            }
            if (!selected.isEmpty()) selected.append('\n');
            selected.append(lines.get(line - 1));
            end = line;
        }
        if (end < mention.startLine) throw new FileMentionException();
        String snapshot = selected.toString();
        return new UserFileAttachment(
                protocolPath, snapshot, sha256(snapshot), mention.startLine, end, truncated);
    }

    private String strictUtf8(byte[] bytes) throws CharacterCodingException {
        if (bytes.length > MAX_SOURCE_FILE_BYTES || containsBinary(bytes)) {
            throw new FileMentionException();
        }
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
    }

    private boolean containsBinary(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Mention(String path, int startLine, int endLine) { }
    private record AttachmentKey(String protocolPath, int startLine, int endLine) { }
    private record LineRange(int start, int end, int nextIndex) { }
}
