package de.mirkosertic.mcp.luceneserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link QueryRuntimeStats}.
 */
class QueryRuntimeStatsTest {

    @Test
    void testInitialState() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        assertThat(stats.getTotalQueries()).isEqualTo(0L);
        assertThat(stats.getAverageDurationMs()).isEqualTo(0.0);
        assertThat(stats.getPercentiles()).isNull();
        assertThat(stats.getMinDurationMs()).isEqualTo(Long.MAX_VALUE);
        assertThat(stats.getMaxDurationMs()).isEqualTo(0L);
    }

    @Test
    void testSingleQuery() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        stats.recordQuery(42L, 10L, 0L, Map.of());

        assertThat(stats.getTotalQueries()).isEqualTo(1L);
        assertThat(stats.getTotalDurationMs()).isEqualTo(42L);
        assertThat(stats.getTotalHitCount()).isEqualTo(10L);
        assertThat(stats.getMinDurationMs()).isEqualTo(42L);
        assertThat(stats.getMaxDurationMs()).isEqualTo(42L);
        assertThat(stats.getAverageDurationMs()).isCloseTo(42.0, within(0.001));
        assertThat(stats.getAverageHitCount()).isCloseTo(10.0, within(0.001));

        final QueryRuntimeStats.Percentiles percentiles = stats.getPercentiles();
        assertThat(percentiles).isNotNull();
        assertThat(percentiles.p50()).isEqualTo(42L);
        assertThat(percentiles.p75()).isEqualTo(42L);
        assertThat(percentiles.p90()).isEqualTo(42L);
        assertThat(percentiles.p95()).isEqualTo(42L);
        assertThat(percentiles.p99()).isEqualTo(42L);
    }

    @Test
    void testMultipleQueries() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        stats.recordQuery(10L, 5L, 0L, Map.of());
        stats.recordQuery(20L, 15L, 0L, Map.of());
        stats.recordQuery(30L, 10L, 0L, Map.of());

        assertThat(stats.getTotalQueries()).isEqualTo(3L);
        assertThat(stats.getTotalDurationMs()).isEqualTo(60L);
        assertThat(stats.getTotalHitCount()).isEqualTo(30L);
        assertThat(stats.getMinDurationMs()).isEqualTo(10L);
        assertThat(stats.getMaxDurationMs()).isEqualTo(30L);
        assertThat(stats.getAverageDurationMs()).isCloseTo(20.0, within(0.001));
        assertThat(stats.getAverageHitCount()).isCloseTo(10.0, within(0.001));
    }

    @Test
    void testPercentileCalculation() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        // Record 100 queries with durations 1..100
        for (int i = 1; i <= 100; i++) {
            stats.recordQuery(i, 0L, 0L, Map.of());
        }

        final QueryRuntimeStats.Percentiles percentiles = stats.getPercentiles();
        assertThat(percentiles).isNotNull();
        assertThat(percentiles.p50()).isEqualTo(50L);
        assertThat(percentiles.p75()).isEqualTo(75L);
        assertThat(percentiles.p90()).isEqualTo(90L);
        assertThat(percentiles.p95()).isEqualTo(95L);
        assertThat(percentiles.p99()).isEqualTo(99L);
    }

    @Test
    void testCircularBufferOverflow() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        // Record 1500 queries (more than the buffer size of 1000)
        for (int i = 1; i <= 1500; i++) {
            stats.recordQuery(i, 0L, 0L, Map.of());
        }

        assertThat(stats.getTotalQueries()).isEqualTo(1500L);

        // Percentiles should still work correctly even after overflow
        final QueryRuntimeStats.Percentiles percentiles = stats.getPercentiles();
        assertThat(percentiles).isNotNull();
        // The last 1000 queries had durations 501..1500 (sorted: 501, 502, ..., 1500)
        // p50: index ceil(50/100 * 1000) - 1 = 500 - 1 = 499 → 501 + 499 = 1000
        // p99: index ceil(99/100 * 1000) - 1 = 990 - 1 = 989 → 501 + 989 = 1490
        assertThat(percentiles.p50()).isEqualTo(1000L);
        assertThat(percentiles.p99()).isEqualTo(1490L);
    }

    @Test
    void testReset() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        stats.recordQuery(100L, 50L, 0L, Map.of());
        stats.recordQuery(200L, 100L, 0L, Map.of());

        assertThat(stats.getTotalQueries()).isEqualTo(2L);

        stats.reset();

        assertThat(stats.getTotalQueries()).isEqualTo(0L);
        assertThat(stats.getTotalDurationMs()).isEqualTo(0L);
        assertThat(stats.getTotalHitCount()).isEqualTo(0L);
        assertThat(stats.getMinDurationMs()).isEqualTo(Long.MAX_VALUE);
        assertThat(stats.getMaxDurationMs()).isEqualTo(0L);
        assertThat(stats.getAverageDurationMs()).isEqualTo(0.0);
        assertThat(stats.getAverageHitCount()).isEqualTo(0.0);
        assertThat(stats.getPercentiles()).isNull();
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        final QueryRuntimeStats stats = new QueryRuntimeStats();
        final int threadCount = 10;
        final int queriesPerThread = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);

        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < queriesPerThread; i++) {
                        stats.recordQuery(i, i * 2L, 0L, Map.of());
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(stats.getTotalQueries()).isEqualTo((long) threadCount * queriesPerThread);
    }

    @Test
    void testFacetTimingTracking() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        stats.recordQuery(10L, 5L, 500L, Map.of("language", 200L, "file_extension", 300L));
        stats.recordQuery(20L, 15L, 700L, Map.of("language", 250L, "file_extension", 450L));

        assertThat(stats.getTotalFacetDurationMicros()).isEqualTo(1200L);
        assertThat(stats.getAverageFacetDurationMicros()).isCloseTo(600.0, within(0.001));

        final Map<String, Long> perField = stats.getPerFieldFacetDurationMicros();
        assertThat(perField).containsEntry("language", 450L);
        assertThat(perField).containsEntry("file_extension", 750L);
    }

    @Test
    void testFacetTimingReset() {
        final QueryRuntimeStats stats = new QueryRuntimeStats();

        stats.recordQuery(10L, 5L, 500L, Map.of("language", 200L));

        assertThat(stats.getTotalFacetDurationMicros()).isEqualTo(500L);
        assertThat(stats.getPerFieldFacetDurationMicros()).containsKey("language");

        stats.reset();

        assertThat(stats.getTotalFacetDurationMicros()).isEqualTo(0L);
        assertThat(stats.getPerFieldFacetDurationMicros()).isEmpty();
        assertThat(stats.getAverageFacetDurationMicros()).isEqualTo(0.0);
    }
}
