package io.github.liumaishenjian.ccjava.protocol;

/** stable v1 connection 状态。 */
public enum ProtocolConnectionState {
    /** 尚未成功 initialize。 */
    NEW,
    /** 已 initialize 并接受新请求。 */
    READY,
    /** 拒绝新请求但允许已接受请求完成。 */
    DRAINING,
    /** 连接已关闭且关联状态已清除。 */
    CLOSED,
    /** 协议不变量被违反，连接失败关闭。 */
    FAILED
}
