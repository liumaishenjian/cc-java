package io.github.liumaishenjian.ccjava.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证取消源的一次性通知与监听注销语义。
 *
 * @since 0.1.0
 */
class CancellationSourceTest {

    @Test
    void notifiesRegisteredListenersOnlyOnce() {
        CancellationSource source = new CancellationSource();
        AtomicInteger notifications = new AtomicInteger();
        source.token().onCancellation(notifications::incrementAndGet);

        assertThat(source.cancel()).isTrue();
        assertThat(source.cancel()).isFalse();
        assertThat(source.token().isCancellationRequested()).isTrue();
        assertThat(notifications).hasValue(1);
    }

    @Test
    void closedRegistrationIsNotNotified() {
        CancellationSource source = new CancellationSource();
        AtomicInteger notifications = new AtomicInteger();
        CancellationToken.Registration registration =
                source.token().onCancellation(notifications::incrementAndGet);

        registration.close();
        source.cancel();

        assertThat(notifications).hasValue(0);
    }

    @Test
    void listenerRegisteredAfterCancellationRunsImmediately() {
        CancellationSource source = new CancellationSource();
        source.cancel();
        AtomicInteger notifications = new AtomicInteger();

        source.token().onCancellation(notifications::incrementAndGet);

        assertThat(notifications).hasValue(1);
    }
}
