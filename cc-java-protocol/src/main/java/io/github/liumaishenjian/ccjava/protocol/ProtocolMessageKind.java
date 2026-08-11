package io.github.liumaishenjian.ccjava.protocol;

/** Stable v1 消息类别。 */
public enum ProtocolMessageKind {
    /** Client 发起且需要关联响应的请求。 */
    REQUEST,
    /** 与一个已接受请求精确关联的响应。 */
    RESPONSE,
    /** Server 主动发送的有序生命周期事件。 */
    EVENT,
    /** 不含不可信正文的结构化错误。 */
    ERROR
}
