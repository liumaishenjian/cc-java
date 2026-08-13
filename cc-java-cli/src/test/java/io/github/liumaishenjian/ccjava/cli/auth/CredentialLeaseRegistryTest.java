package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** generation、fence、cancel/close 与 terminal 生命周期契约测试。 */
class CredentialLeaseRegistryTest {
    @Test void generationCannotChangeWhileProfileHasActiveRuns() {
        CredentialLeaseRegistry registry=new CredentialLeaseRegistry();
        var first=registry.acquire("anthropic","personal",4,()->{});
        assertThatThrownBy(()->registry.acquire("anthropic","personal",5,()->{}))
                .isInstanceOf(ProviderAuthException.class);
        first.close();
        try(var next=registry.acquire("anthropic","personal",5,()->{})) {
            assertThat(next.generation()).isEqualTo(5);
        }
    }

    @Test void finalLeaseCloseAllowsRepeatedFutureGenerationsWithoutStaleState() {
        try (CredentialLeaseRegistry registry = new CredentialLeaseRegistry()) {
            for (long generation = 1; generation <= 100; generation++) {
                long current = generation;
                var first = registry.acquire("anthropic", "personal", current, () -> { });
                var second = registry.acquire("anthropic", "personal", current, () -> { });
                first.close();
                assertThatThrownBy(() -> registry.acquire("anthropic", "personal", current + 1, () -> { }))
                        .isInstanceOf(ProviderAuthException.class);
                second.close();
                try (var next = registry.acquire("anthropic", "personal", current + 1, () -> { })) {
                    assertThat(next.generation()).isEqualTo(current + 1);
                }
            }
        }
    }

    @Test void fenceCancelsClosesAndWaitsForExactlyOneTerminal() throws Exception {
        CredentialLeaseRegistry registry=new CredentialLeaseRegistry();
        AtomicInteger cancelled=new AtomicInteger(),closed=new AtomicInteger();
        var lease=registry.acquire("openrouter","work",7,closed::incrementAndGet);
        lease.bindCancellation(cancelled::incrementAndGet);
        CountDownLatch started=new CountDownLatch(1);
        var drainer=Thread.startVirtualThread(()->{started.countDown();assertThat(registry.fenceAndDrain(
                "openrouter","work",Duration.ofSeconds(2),CancellationToken.none())).isTrue();});
        started.await();
        while(cancelled.get()==0) Thread.onSpinWait();
        assertThat(registry.activeCount("openrouter","work")).isOne();
        lease.close(); lease.close(); drainer.join();
        assertThat(cancelled).hasValue(1); assertThat(closed).hasValue(1);
        assertThatThrownBy(()->registry.acquire("openrouter","work",7,()->{}))
                .isInstanceOf(ProviderAuthException.class);
    }
}