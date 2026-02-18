package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.Map;

/**
 * Response DTO for the getIndexStats tool.
 */
public record IndexStatsResponse(
        boolean success,
        long documentCount,
        String indexPath,
        int schemaVersion,
        String softwareVersion,
        String buildTimestamp,
        Map<String, DateFieldHint> dateFieldHints,
        Map<String, LemmatizerCacheMetrics> lemmatizerCacheMetrics,
        QueryRuntimeMetrics queryRuntimeMetrics,
        String error
) {
    /**
     * Min/max range hint for a date field (epoch millis converted to ISO-8601).
     */
    public record DateFieldHint(String minDate, String maxDate) {
    }

    /**
     * Lemmatizer cache metrics for a specific language analyzer.
     */
    public record LemmatizerCacheMetrics(
            String language,
            String hitRate,
            long totalHits,
            long totalMisses,
            long cacheSize,
            long evictions
    ) {
    }

    /**
     * Aggregate search query performance statistics.
     */
    public record QueryRuntimeMetrics(
            long totalQueries,
            String averageDurationMs,
            long minDurationMs,
            long maxDurationMs,
            String averageHitCount,
            Long p50Ms,
            Long p75Ms,
            Long p90Ms,
            Long p95Ms,
            Long p99Ms,
            String averageFacetDurationMs,
            Map<String, String> perFieldAverageFacetDurationMs
    ) {
    }

    public static IndexStatsResponse success(final long documentCount, final String indexPath,
                                              final int schemaVersion, final String softwareVersion,
                                              final String buildTimestamp,
                                              final Map<String, DateFieldHint> dateFieldHints,
                                              final Map<String, LemmatizerCacheMetrics> lemmatizerCacheMetrics,
                                              final QueryRuntimeMetrics queryRuntimeMetrics) {
        return new IndexStatsResponse(true, documentCount, indexPath, schemaVersion, softwareVersion,
                buildTimestamp, dateFieldHints, lemmatizerCacheMetrics, queryRuntimeMetrics, null);
    }

    public static IndexStatsResponse error(final String errorMessage) {
        return new IndexStatsResponse(false, 0, null, 0, null, null, null, null, null, errorMessage);
    }
}
