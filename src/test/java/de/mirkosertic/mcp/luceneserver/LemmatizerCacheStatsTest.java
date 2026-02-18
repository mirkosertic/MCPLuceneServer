package de.mirkosertic.mcp.luceneserver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LemmatizerCacheStatsTest {

    @Test
    void testMetricsTracking() {
        final LemmatizerCacheStats stats = new LemmatizerCacheStats();

        assertThat(stats.getTotalRequests()).isEqualTo(0);
        assertThat(stats.getCacheHits()).isEqualTo(0);
        assertThat(stats.getCacheMisses()).isEqualTo(0);
        assertThat(stats.getEvictions()).isEqualTo(0);
        assertThat(stats.getCurrentSize()).isEqualTo(0);
        assertThat(stats.getHitRate()).isEqualTo(0.0);

        // Record some hits
        stats.recordHit();
        stats.recordHit();
        stats.recordHit();

        assertThat(stats.getTotalRequests()).isEqualTo(3);
        assertThat(stats.getCacheHits()).isEqualTo(3);
        assertThat(stats.getCacheMisses()).isEqualTo(0);
        assertThat(stats.getHitRate()).isEqualTo(100.0);

        // Record some misses
        stats.recordMiss();

        assertThat(stats.getTotalRequests()).isEqualTo(4);
        assertThat(stats.getCacheHits()).isEqualTo(3);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getHitRate()).isEqualTo(75.0);

        // Record evictions
        stats.recordEviction();
        stats.recordEviction();

        assertThat(stats.getEvictions()).isEqualTo(2);

        // Update cache size
        stats.setCurrentSize(100);
        assertThat(stats.getCurrentSize()).isEqualTo(100);
    }

    @Test
    void testHitRateCalculation() {
        final LemmatizerCacheStats stats = new LemmatizerCacheStats();

        // 0 requests -> 0% hit rate
        assertThat(stats.getHitRate()).isEqualTo(0.0);

        // 5 hits out of 10 total -> 50%
        stats.recordHit();
        stats.recordHit();
        stats.recordHit();
        stats.recordHit();
        stats.recordHit();
        stats.recordMiss();
        stats.recordMiss();
        stats.recordMiss();
        stats.recordMiss();
        stats.recordMiss();

        assertThat(stats.getTotalRequests()).isEqualTo(10);
        assertThat(stats.getHitRate()).isEqualTo(50.0);
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        final LemmatizerCacheStats stats = new LemmatizerCacheStats();
        final int threadCount = 10;
        final int operationsPerThread = 1000;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);

        // Each thread records half hits, half misses
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (j % 2 == 0) {
                            stats.recordHit();
                        } else {
                            stats.recordMiss();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify all operations were recorded
        final long expectedTotal = (long) threadCount * operationsPerThread;
        assertThat(stats.getTotalRequests()).isEqualTo(expectedTotal);
        assertThat(stats.getCacheHits() + stats.getCacheMisses()).isEqualTo(expectedTotal);

        // Should be 50% hit rate
        assertThat(stats.getHitRate()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void testMetricsStringFormat() {
        final LemmatizerCacheStats stats = new LemmatizerCacheStats();

        stats.recordHit();
        stats.recordHit();
        stats.recordMiss();
        stats.setCurrentSize(42);
        stats.recordEviction();

        final String metrics = stats.getMetrics();

        assertThat(metrics).contains("total=3");
        assertThat(metrics).contains("hits=2");
        assertThat(metrics).contains("misses=1");
        // Accept both "66.7%" and "66,7%" (locale-dependent decimal separator)
        assertThat(metrics).containsAnyOf("hitRate=66.7%", "hitRate=66,7%");
        assertThat(metrics).contains("size=42");
        assertThat(metrics).contains("evictions=1");
    }
}
