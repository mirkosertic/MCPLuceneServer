package de.mirkosertic.mcp.luceneserver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe aggregate statistics collector for query runtime performance.
 *
 * <p>Tracks query durations and hit counts using atomic counters for lock-free
 * increments on the hot path. A circular buffer (guarded by a dedicated lock)
 * stores the last 1000 query durations for percentile computation.</p>
 */
public class QueryRuntimeStats {

    private static final int BUFFER_SIZE = 1000;

    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong totalHitCount = new AtomicLong(0);
    private final AtomicLong minDurationMs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDurationMs = new AtomicLong(0);
    private final AtomicLong totalFacetDurationMicros = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> perFieldFacetDurationMicros = new ConcurrentHashMap<>();

    private final long[] buffer = new long[BUFFER_SIZE];
    private int bufferIndex = 0;
    private boolean bufferFilled = false;
    private final Object lock = new Object();

    /**
     * Percentile values computed from the last 1000 recorded query durations.
     */
    public record Percentiles(long p50, long p75, long p90, long p95, long p99) {
    }

    /**
     * Records a completed query.
     *
     * @param durationMs            the query duration in milliseconds
     * @param totalHits             the total number of matching documents
     * @param facetDurationMicros   the total facet computation time in microseconds
     * @param perFieldFacetMicros   per-field facet computation times in microseconds (may be null)
     */
    public void recordQuery(final long durationMs, final long totalHits,
                            final long facetDurationMicros, final Map<String, Long> perFieldFacetMicros) {
        totalQueries.incrementAndGet();
        totalDurationMs.addAndGet(durationMs);
        totalHitCount.addAndGet(totalHits);

        // CAS loop for min
        long current;
        do {
            current = minDurationMs.get();
            if (durationMs >= current) break;
        } while (!minDurationMs.compareAndSet(current, durationMs));

        // CAS loop for max
        do {
            current = maxDurationMs.get();
            if (durationMs <= current) break;
        } while (!maxDurationMs.compareAndSet(current, durationMs));

        // Write to circular buffer (synchronized)
        synchronized (lock) {
            buffer[bufferIndex] = durationMs;
            bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
            if (!bufferFilled && bufferIndex == 0) {
                bufferFilled = true;
            }
        }

        totalFacetDurationMicros.addAndGet(facetDurationMicros);
        if (perFieldFacetMicros != null) {
            for (final var entry : perFieldFacetMicros.entrySet()) {
                perFieldFacetDurationMicros.computeIfAbsent(entry.getKey(), k -> new AtomicLong())
                        .addAndGet(entry.getValue());
            }
        }
    }

    /**
     * Computes percentiles from the last 1000 recorded query durations.
     *
     * @return percentiles, or null if no queries have been recorded yet
     */
    public Percentiles getPercentiles() {
        final long[] snapshot;
        final int count;

        synchronized (lock) {
            if (totalQueries.get() == 0) {
                return null;
            }
            if (bufferFilled) {
                snapshot = Arrays.copyOf(buffer, BUFFER_SIZE);
                count = BUFFER_SIZE;
            } else {
                snapshot = Arrays.copyOf(buffer, bufferIndex);
                count = bufferIndex;
            }
        }

        if (count == 0) {
            return null;
        }

        Arrays.sort(snapshot, 0, count);

        final long p50 = percentileValue(snapshot, count, 50);
        final long p75 = percentileValue(snapshot, count, 75);
        final long p90 = percentileValue(snapshot, count, 90);
        final long p95 = percentileValue(snapshot, count, 95);
        final long p99 = percentileValue(snapshot, count, 99);

        return new Percentiles(p50, p75, p90, p95, p99);
    }

    private static long percentileValue(final long[] sortedData, final int count, final int percentile) {
        final int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
        return sortedData[Math.max(0, Math.min(index, count - 1))];
    }

    /**
     * Resets all counters and clears the circular buffer.
     */
    public void reset() {
        totalQueries.set(0);
        totalDurationMs.set(0);
        totalHitCount.set(0);
        minDurationMs.set(Long.MAX_VALUE);
        maxDurationMs.set(0);
        synchronized (lock) {
            bufferIndex = 0;
            bufferFilled = false;
            Arrays.fill(buffer, 0L);
        }
        totalFacetDurationMicros.set(0);
        perFieldFacetDurationMicros.clear();
    }

    /**
     * Returns the total number of queries recorded since the last reset.
     */
    public long getTotalQueries() {
        return totalQueries.get();
    }

    /**
     * Returns the sum of all recorded query durations in milliseconds.
     */
    public long getTotalDurationMs() {
        return totalDurationMs.get();
    }

    /**
     * Returns the sum of all recorded hit counts.
     */
    public long getTotalHitCount() {
        return totalHitCount.get();
    }

    /**
     * Returns the minimum recorded query duration in milliseconds.
     * Returns {@link Long#MAX_VALUE} if no queries have been recorded.
     */
    public long getMinDurationMs() {
        return minDurationMs.get();
    }

    /**
     * Returns the maximum recorded query duration in milliseconds.
     */
    public long getMaxDurationMs() {
        return maxDurationMs.get();
    }

    /**
     * Returns the average query duration in milliseconds, or 0.0 if no queries recorded.
     */
    public double getAverageDurationMs() {
        final long queries = totalQueries.get();
        if (queries == 0) {
            return 0.0;
        }
        return (double) totalDurationMs.get() / queries;
    }

    /**
     * Returns the average hit count per query, or 0.0 if no queries recorded.
     */
    public double getAverageHitCount() {
        final long queries = totalQueries.get();
        if (queries == 0) {
            return 0.0;
        }
        return (double) totalHitCount.get() / queries;
    }

    /**
     * Returns the cumulative facet computation time in microseconds since last reset.
     */
    public long getTotalFacetDurationMicros() {
        return totalFacetDurationMicros.get();
    }

    /**
     * Returns a snapshot of per-field cumulative facet computation times in microseconds.
     */
    public Map<String, Long> getPerFieldFacetDurationMicros() {
        final Map<String, Long> snapshot = new HashMap<>();
        for (final var entry : perFieldFacetDurationMicros.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    /**
     * Returns the average facet computation time per query in microseconds, or 0.0 if no queries recorded.
     */
    public double getAverageFacetDurationMicros() {
        final long queries = totalQueries.get();
        if (queries == 0) {
            return 0.0;
        }
        return (double) totalFacetDurationMicros.get() / queries;
    }
}
