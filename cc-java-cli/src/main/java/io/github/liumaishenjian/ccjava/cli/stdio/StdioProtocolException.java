package io.github.liumaishenjian.ccjava.cli.stdio;

import java.io.Serial;
import java.util.Objects;

/**
 * 表示能够安全转换成 {@code protocol.error} 的协议边界错误。
 *
 * <p>异常只保存稳定错误码、脱敏消息和可用的 Request ID，不携带原始输入行，
 * 避免把 Prompt、Secret 或畸形大输入写入 stderr 或事件。</p>
 *
 * @since 0.1.0
 */
public final class StdioProtocolException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 可供 Client 确定性分支处理的稳定错误码。 */
    private final String code;

    /** 原命令的关联 ID；无法从输入恢复时使用协议保留值。 */
    private final String requestId;

    /**
     * 创建协议错误。
     *
     * @param code 稳定错误码
     * @param requestId 可恢复的请求 ID；不可恢复时使用保留值
     * @param message 脱敏的人类说明
     */
    public StdioProtocolException(String code, String requestId, String message) {
        super(Objects.requireNonNull(message, "message 不能为空"));
        this.code = requireText(code, "code");
        this.requestId = requireText(requestId, "requestId");
    }

    /**
     * 返回稳定错误码。
     *
     * @return 非空错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回请求关联标识。
     *
     * @return 原请求 ID 或保留值
     */
    public String requestId() {
        return requestId;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
