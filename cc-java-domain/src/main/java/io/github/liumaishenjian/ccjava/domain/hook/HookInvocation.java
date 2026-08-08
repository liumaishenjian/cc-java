package io.github.liumaishenjian.ccjava.domain.hook;

import io.github.liumaishenjian.ccjava.domain.JsonObject;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Objects;
import java.util.Optional;

/**
 * 传给 Hook Handler 的最小、不可变生命周期请求。
 *
 * <p>调用者必须在构造前完成脱敏；该契约不会自动把 Tool 参数、文件正文、命令
 * 或 Provider 密钥复制到 Hook 输入。{@code subject} 是用于 Matcher 的有界稳定
 * 名称，{@code data} 只承载本项目明确定义的摘要字段。</p>
 *
 * @param event 事件种类
 * @param sessionId 所属 Session
 * @param runId 可选 Run；Session 级事件为空
 * @param subject Matcher 使用的有界主体名，例如 Tool 名称
 * @param data 已脱敏的结构化摘要
 * @since 0.1.0
 */
public record HookInvocation(
        HookEventKind event,
        SessionId sessionId,
        Optional<RunId> runId,
        String subject,
        JsonObject data) {

    /** 单个 Hook 主体名的最大 Unicode 字符数。 */
    public static final int MAX_SUBJECT_CHARACTERS = 256;

    /**
     * 校验生命周期请求的标识和输入边界。
     */
    public HookInvocation {
        event = Objects.requireNonNull(event, "event 不能为空");
        sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        runId = Objects.requireNonNull(runId, "runId 不能为空");
        subject = requireSubject(subject);
        data = Objects.requireNonNull(data, "data 不能为空");
    }

    private static String requireSubject(String value) {
        Objects.requireNonNull(value, "subject 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("subject 不能为空白");
        }
        if (value.codePointCount(0, value.length()) > MAX_SUBJECT_CHARACTERS) {
            throw new IllegalArgumentException("subject 超过字符上限");
        }
        return value;
    }
}
