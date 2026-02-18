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

    public static IndexStatsResponse success(final long documentCount, final String indexPath,
                                              final int schemaVersion, final String softwareVersion,
                                              final String buildTimestamp,
                                              final Map<String, DateFieldHint> dateFieldHints,
                                              final Map<String, LemmatizerCacheMetrics> lemmatizerCacheMetrics) {
        return new IndexStatsResponse(true, documentCount, indexPath, schemaVersion, softwareVersion,
                buildTimestamp, dateFieldHints, lemmatizerCacheMetrics, null);
    }

    public static IndexStatsResponse error(final String errorMessage) {
        return new IndexStatsResponse(false, 0, null, 0, null, null, null, null, errorMessage);
    }
}
