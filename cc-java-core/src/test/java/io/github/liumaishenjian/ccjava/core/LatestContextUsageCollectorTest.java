package io.github.liumaishenjian.ccjava.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.domain.ContextCapacity;
import io.github.liumaishenjian.ccjava.domain.ContextEstimateKind;
import io.github.liumaishenjian.ccjava.domain.ContextProjection;
import io.github.liumaishenjian.ccjava.domain.ContextUsage;
import io.github.liumaishenjian.ccjava.domain.ContextUsageView;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LatestContextUsageCollectorTest {

    @Test
    void retainsOnlyLatestViewAndDropsPublicationAfterClose() {
        LatestContextUsageCollector collector = new LatestContextUsageCollector();
        ContextUsageView first = view(1);
        ContextUsageView latest = view(2);

        collector.publish(first);
        collector.publish(latest);

        assertThat(collector.latest()).contains(latest);
        collector.close();
        collector.publish(first);
        assertThat(collector.latest()).isEmpty();
    }

    @Test
    void closeWinsAgainstAConcurrentLatePublication() throws Exception {
        LatestContextUsageCollector collector = new LatestContextUsageCollector();
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> close = executor.submit(() -> {
                bothReady.await();
                collector.close();
                return null;
            });
            Future<?> publish = executor.submit(() -> {
                bothReady.await();
                close.get(5, TimeUnit.SECONDS);
                collector.publish(view(3));
                return null;
            });
            publish.get(5, TimeUnit.SECONDS);
            assertThat(collector.latest()).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ContextUsageView view(long revision) {
        ContextUsage usage = new ContextUsage(1, 0, 1, 1, 1, 4, 6, ContextEstimateKind.ESTIMATED);
        return ContextUsageView.prepared(new ContextProjection(List.of(), usage, List.of(), revision),
                new ContextCapacity("model", 10, 0, 0));
    }
}
