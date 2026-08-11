package io.github.liumaishenjian.ccjava.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * stable v1 initialize、序号、关联、幂等与 graceful drain 状态机。
 *
 * <p>本类不执行 Runtime；它只验证连接级状态。响应只能关联到已接受且尚未响应的请求，
 * 幂等缓存和待响应集合均有界，任何未知、重复或乱序 lifecycle 都会 Fail Closed。</p>
 *
 * @since 0.1.0
 */
public final class ProtocolConnection {
    private static final int MAX_IDEMPOTENCY = 1024;
    private static final int MAX_PENDING_REQUESTS = 256;

    private final CapabilityToken token;
    private final Set<ProtocolFeature> serverFeatures;
    private final LinkedHashMap<String, ResponseIdentity> idempotency = new LinkedHashMap<>();
    private final Map<String, AcceptedRequest> pending = new LinkedHashMap<>();
    private ProtocolConnectionState state = ProtocolConnectionState.NEW;
    private long inbound;
    private Set<ProtocolFeature> negotiated = Set.of();

    /**
     * 创建尚未 initialize 的 stable v1 连接状态机。
     *
     * @param token 连接初始化必须精确匹配的 capability token
     * @param serverFeatures Server 可协商的能力集合
     */
    public ProtocolConnection(CapabilityToken token, Set<ProtocolFeature> serverFeatures) {
        this.token = Objects.requireNonNull(token, "token 不能为空");
        this.serverFeatures = Set.copyOf(Objects.requireNonNull(serverFeatures, "serverFeatures 不能为空"));
    }

    /**
     * Initialize 恰一次，并求 Client/Server 能力交集。
     *
     * @param presentedToken Client 提供的 capability token
     * @param clientVersion Client stable protocol 版本
     * @param requested Client 请求的能力集合
     * @return Server 支持且 Client 请求的能力交集
     * @throws ProtocolCodecException token、版本或连接状态非法时
     */
    public synchronized Set<ProtocolFeature> initialize(
            String presentedToken,
            ProtocolVersion clientVersion,
            Set<ProtocolFeature> requested) throws ProtocolCodecException {
        return initialize(presentedToken, clientVersion, requested, 0);
    }

    /**
     * initialize 恰一次，并把 initialize 信封序号纳入后续严格入站序列。
     *
     * <p>{@code initializeSequence=0} 仅供无 wire 信封的嵌入式兼容调用；稳定 handler 必须
     * 传入正序号，下一条请求只能使用其后继值。</p>
     *
     * @param presentedToken Client 提供的 capability token
     * @param clientVersion Client stable protocol 版本
     * @param requested Client 请求的能力集合
     * @param initializeSequence initialize 信封序号；嵌入式调用可为零
     * @return Server 支持且 Client 请求的能力交集
     * @throws ProtocolCodecException token、版本、序号或连接状态非法时
     */
    public synchronized Set<ProtocolFeature> initialize(
            String presentedToken,
            ProtocolVersion clientVersion,
            Set<ProtocolFeature> requested,
            long initializeSequence) throws ProtocolCodecException {
        if (state != ProtocolConnectionState.NEW) {
            throw fail("INITIALIZE_ONCE");
        }
        if (initializeSequence < 0) {
            throw fail("SEQUENCE");
        }
        if (!token.matches(presentedToken)) {
            throw fail("UNAUTHORIZED");
        }
        if (Objects.requireNonNull(clientVersion, "clientVersion 不能为空").major()
                != ProtocolVersion.V1_0.major()) {
            throw fail("INCOMPATIBLE_MAJOR");
        }
        Objects.requireNonNull(requested, "requested 不能为空");
        EnumSet<ProtocolFeature> intersection = serverFeatures.isEmpty()
                ? EnumSet.noneOf(ProtocolFeature.class)
                : EnumSet.copyOf(serverFeatures);
        intersection.retainAll(requested);
        negotiated = Set.copyOf(intersection);
        inbound = initializeSequence;
        state = ProtocolConnectionState.READY;
        return negotiated;
    }

    /**
     * 验证请求状态、序号和身份；已完成的幂等请求返回原响应 identity。
     *
     * @param envelope 待接受的请求信封
     * @return 幂等重放命中时的响应消息 ID
     * @throws ProtocolCodecException 状态、序号、身份或容量边界非法时
     */
    public synchronized Optional<String> accept(ProtocolEnvelope envelope) throws ProtocolCodecException {
        requireAccepting();
        Objects.requireNonNull(envelope, "envelope 不能为空");
        if (envelope.kind() != ProtocolMessageKind.REQUEST) {
            throw fail("REQUEST_REQUIRED");
        }
        if (envelope.sequence() != inbound + 1) {
            throw fail("SEQUENCE");
        }
        inbound = envelope.sequence();
        if (pending.containsKey(envelope.messageId())) {
            throw fail("DUPLICATE_MESSAGE_ID");
        }
        String fingerprint = semanticFingerprint(envelope);
        Optional<ResponseIdentity> cached = envelope.idempotencyKey().map(idempotency::get);
        if (cached.isPresent()) {
            ResponseIdentity response = cached.orElseThrow();
            if (!response.semanticFingerprint().equals(fingerprint)) {
                throw fail("IDEMPOTENCY_CONFLICT");
            }
            return Optional.of(response.messageId());
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            throw fail("PENDING_LIMIT");
        }
        pending.put(envelope.messageId(), new AcceptedRequest(
                envelope.type(), envelope.correlationId(), envelope.sessionId(), envelope.runId(),
                envelope.sequence(), fingerprint, envelope.idempotencyKey()));
        return Optional.empty();
    }

    /**
     * 在响应 durable 后登记关联与幂等结果。
     *
     * <p>只有先前成功 {@link #accept(ProtocolEnvelope)} 的请求可登记一次响应。</p>
     *
     * @param request 先前已接受的精确请求信封
     * @param response durable 后的响应信封
     * @throws ProtocolCodecException 响应未与已接受请求精确关联时
     */
    public synchronized void recordResponse(
            ProtocolEnvelope request,
            ProtocolEnvelope response) throws ProtocolCodecException {
        if (state != ProtocolConnectionState.READY && state != ProtocolConnectionState.DRAINING) {
            throw fail("NOT_READY");
        }
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(response, "response 不能为空");
        AcceptedRequest accepted = pending.get(request.messageId());
        if (accepted == null) {
            throw fail("REQUEST_NOT_ACCEPTED");
        }
        if (response.kind() != ProtocolMessageKind.RESPONSE
                || !response.version().equals(request.version())
                || response.messageId().equals(request.messageId())
                || !response.correlationId().equals(request.messageId())
                || !accepted.requestType().equals(request.type())
                || !accepted.correlationId().equals(request.correlationId())
                || !accepted.sessionId().equals(request.sessionId())
                || !accepted.runId().equals(request.runId())
                || accepted.requestSequence() != request.sequence()
                || !accepted.semanticFingerprint().equals(semanticFingerprint(request))
                || !response.sessionId().equals(request.sessionId())
                || !response.runId().equals(request.runId())
                || response.sequence() < request.sequence()) {
            throw fail("RESPONSE_CORRELATION");
        }
        pending.remove(request.messageId());
        accepted.idempotencyKey().ifPresent(key -> remember(
                key, new ResponseIdentity(response.messageId(), response.sequence(),
                        accepted.semanticFingerprint())));
    }

    /**
     * 兼容只提供响应 ID 的内部调用；响应关联仍由已接受请求身份确定。
     *
     * @param request 先前已接受的精确请求信封
     * @param responseMessageId 新响应的规范消息 ID
     * @throws ProtocolCodecException 请求未接受或关联无效时
     */
    public synchronized void recordResponse(
            ProtocolEnvelope request,
            String responseMessageId) throws ProtocolCodecException {
        ProtocolEnvelope response = new ProtocolEnvelope(
                request.version(), ProtocolMessageKind.RESPONSE, request.type() + ".response",
                responseMessageId, request.messageId(), request.sessionId(), request.runId(),
                request.sequence(), Optional.empty(), request.payload());
        recordResponse(request, response);
    }

    /** 开始 drain；之后拒绝新请求，但允许已接受请求完成响应。 */
    public synchronized void beginDrain() {
        if (state == ProtocolConnectionState.READY) {
            state = ProtocolConnectionState.DRAINING;
        }
    }

    /** 幂等关闭并清除连接级关联状态。 */
    public synchronized void close() {
        if (state == ProtocolConnectionState.CLOSED) {
            return;
        }
        state = ProtocolConnectionState.CLOSED;
        idempotency.clear();
        pending.clear();
    }

    /**
     * 返回当前连接生命周期状态。
     *
     * @return stable v1 connection 状态
     */
    public synchronized ProtocolConnectionState state() {
        return state;
    }

    /**
     * 返回 initialize 时固定的能力交集。
     *
     * @return 不可变 negotiated features
     */
    public synchronized Set<ProtocolFeature> negotiatedFeatures() {
        return negotiated;
    }

    /**
     * 返回已接受但尚未登记 durable 响应的请求数。
     *
     * @return 当前 pending request 数量
     */
    public synchronized int pendingRequests() {
        return pending.size();
    }

    private void requireAccepting() throws ProtocolCodecException {
        if (state != ProtocolConnectionState.READY) {
            throw fail("NOT_ACCEPTING");
        }
    }

    private void remember(String key, ResponseIdentity response) {
        ResponseIdentity existing = idempotency.putIfAbsent(key, response);
        if (existing != null && !existing.equals(response)) {
            throw new IllegalStateException("幂等响应 identity 冲突");
        }
        if (existing == null && idempotency.size() > MAX_IDEMPOTENCY) {
            idempotency.remove(idempotency.keySet().iterator().next());
        }
    }

    private ProtocolCodecException fail(String code) {
        state = ProtocolConnectionState.FAILED;
        return new ProtocolCodecException(code);
    }

    /**
     * 计算不含原始正文的规范语义指纹。
     *
     * <p>Object key 排序，Array 顺序保留；摘要只留在有界内存状态，不进入日志或诊断。</p>
     */
    private static String semanticFingerprint(ProtocolEnvelope envelope) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, envelope.type());
            update(digest, envelope.correlationId());
            update(digest, envelope.sessionId().orElse(""));
            update(digest, envelope.runId().orElse(""));
            updateCanonical(digest, envelope.payload());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void updateCanonical(MessageDigest digest, JsonNode node) {
        if (node.isObject()) {
            digest.update((byte) '{');
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                update(digest, name);
                updateCanonical(digest, node.get(name));
            }
            digest.update((byte) '}');
        } else if (node.isArray()) {
            digest.update((byte) '[');
            node.forEach(child -> updateCanonical(digest, child));
            digest.update((byte) ']');
        } else {
            update(digest, node.getNodeType().name());
            update(digest, node.toString());
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private record AcceptedRequest(
            String requestType,
            String correlationId,
            Optional<String> sessionId,
            Optional<String> runId,
            long requestSequence,
            String semanticFingerprint,
            Optional<String> idempotencyKey) {
    }

    private record ResponseIdentity(
            String messageId,
            long responseSequence,
            String semanticFingerprint) {
    }
}
