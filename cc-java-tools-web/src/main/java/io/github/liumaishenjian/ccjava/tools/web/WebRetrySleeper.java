package io.github.liumaishenjian.ccjava.tools.web;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.time.Duration;

/** Web Adapter bounded backoff 的可替换等待端口。 */
@FunctionalInterface
interface WebRetrySleeper {
    /** 等待一次退避；实现必须响应取消和中断。 */
    void sleep(Duration delay, CancellationToken cancellation) throws WebSearchException;
}
