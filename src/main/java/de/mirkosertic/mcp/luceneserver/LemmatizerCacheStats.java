package de.mirkosertic.mcp.luceneserver;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics collector for lemmatizer cache performance.
 *
 * <p>Tracks cache hits, misses, evictions, and provides computed metrics like hit rate.
 * All operations are thread-safe for concurrent use during document indexing.</p>
 */
public class LemmatizerCacheStats {

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong cacheSize = new AtomicLong(0);

    /**
     * Records a cache hit (token found in cache).
     */
    public void recordHit() {
        totalRequests.incrementAndGet();
        cacheHits.incrementAndGet();
    }

    /**
     * Records multiple cache hits.
     *
     * @param count the number of cache hits to record
     */
    public void recordHits(final int count) {
        if (count > 0) {
            totalRequests.addAndGet(count);
            cacheHits.addAndGet(count);
        }
    }

    /**
     * Records a cache miss (token not found in cache, required lemmatization).
     */
    public void recordMiss() {
        totalRequests.incrementAndGet();
        cacheMisses.incrementAndGet();
    }

    /**
     * Records multiple cache misses.
     *
     * @param count the number of cache misses to record
     */
    public void recordMisses(final int count) {
        if (count > 0) {
            totalRequests.addAndGet(count);
            cacheMisses.addAndGet(count);
        }
    }

    /**
     * Records a cache entry eviction.
     */
    public void recordEviction() {
        evictions.incrementAndGet();
    }

    /**
     * Updates the current cache size.
     *
     * @param size the current number of entries in the cache
     */
    public void setCurrentSize(final long size) {
        cacheSize.set(size);
    }

    /**
     * Returns the total number of lemmatization requests.
     */
    public long getTotalRequests() {
        return totalRequests.get();
    }

    /**
     * Returns the number of cache hits.
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Returns the number of cache misses.
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Returns the number of cache evictions.
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * Returns the current cache size (number of entries).
     */
    public long getCurrentSize() {
        return cacheSize.get();
    }

    /**
     * Calculates and returns the cache hit rate as a percentage (0-100).
     *
     * @return hit rate percentage, or 0.0 if no requests have been made
     */
    public double getHitRate() {
        final long total = totalRequests.get();
        if (total == 0) {
            return 0.0;
        }
        return (cacheHits.get() * 100.0) / total;
    }

    /**
     * Returns a formatted string representation of all metrics.
     *
     * @return human-readable metrics summary
     */
    public String getMetrics() {
        return String.format(
                "LemmatizerCacheStats[total=%d, hits=%d, misses=%d, hitRate=%.1f%%, size=%d, evictions=%d]",
                getTotalRequests(),
                getCacheHits(),
                getCacheMisses(),
                getHitRate(),
                getCurrentSize(),
                getEvictions()
        );
    }

    @Override
    public String toString() {
        return getMetrics();
    }
}
