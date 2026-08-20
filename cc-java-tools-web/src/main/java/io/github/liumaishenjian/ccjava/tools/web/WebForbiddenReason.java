package io.github.liumaishenjian.ccjava.tools.web;

/** HTTP 403 在不读取响应正文前提下可观察的安全原因。 */
public enum WebForbiddenReason {
    /** 受信认证挑战头存在。 */ AUTHORIZATION_REQUIRED,
    /** 受信代理头明确报告 allowlist/ACL 阻断。 */ USER_AGENT_OR_ACL,
    /** 没有足够类型化证据进一步区分。 */ FORBIDDEN
}
