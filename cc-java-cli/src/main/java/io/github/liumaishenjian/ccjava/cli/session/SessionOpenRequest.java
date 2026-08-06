package io.github.liumaishenjian.ccjava.cli.session;

import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Objects;
import java.util.Optional;

/**
 * 架构边缘的持久 Session 选择请求。
 *
 * @param mode 打开模式
 * @param sessionId Resume/Fork/Inspect 的目标 ID
 * @since 0.6.0
 */
public record SessionOpenRequest(SessionOpenMode mode, Optional<SessionId> sessionId) {

    /** 校验模式与 ID 的组合。 */
    public SessionOpenRequest {
        mode = Objects.requireNonNull(mode, "mode 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        boolean requiresId = mode == SessionOpenMode.RESUME
                || mode == SessionOpenMode.FORK
                || mode == SessionOpenMode.INSPECT;
        if (requiresId != sessionId.isPresent()) {
            throw new IllegalArgumentException("Session 打开模式与 ID 不匹配");
        }
    }

    /**
     * 创建默认的新 Session 请求。
     *
     * @return 创建新 Session 的请求
     */
    public static SessionOpenRequest create() {
        return new SessionOpenRequest(SessionOpenMode.CREATE, Optional.empty());
    }

    /**
     * 创建继续当前 Workspace 最近 clean Session 的请求。
     *
     * @return Continue 请求
     */
    public static SessionOpenRequest continueLatest() {
        return new SessionOpenRequest(SessionOpenMode.CONTINUE, Optional.empty());
    }

    /**
     * 创建按 ID 恢复同一规范 Session 的请求。
     *
     * @param sessionId 已由命令边界校验的目标 Session ID
     * @return Resume 请求
     */
    public static SessionOpenRequest resume(SessionId sessionId) {
        return new SessionOpenRequest(SessionOpenMode.RESUME, Optional.of(Objects.requireNonNull(sessionId, "sessionId 不能为空")));
    }
}
