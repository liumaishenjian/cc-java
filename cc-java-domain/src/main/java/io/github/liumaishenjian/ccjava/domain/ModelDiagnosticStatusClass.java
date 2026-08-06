package io.github.liumaishenjian.ccjava.domain;

/**
 * 诊断事件允许记录的粗粒度 HTTP 状态组。
 *
 * @since 0.1.0
 */
public enum ModelDiagnosticStatusClass {
    /** 没有可信 HTTP 状态。 */
    NONE,
    /** HTTP 4xx。 */
    CLIENT_ERROR,
    /** HTTP 5xx。 */
    SERVER_ERROR
}
