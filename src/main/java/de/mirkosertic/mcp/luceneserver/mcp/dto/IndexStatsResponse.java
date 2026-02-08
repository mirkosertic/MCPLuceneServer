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
        String error
) {
    /**
     * Min/max range hint for a date field (epoch millis converted to ISO-8601).
     */
    public record DateFieldHint(String minDate, String maxDate) {
    }

    public static IndexStatsResponse success(final long documentCount, final String indexPath,
                                              final int schemaVersion, final String softwareVersion,
                                              final String buildTimestamp,
                                              final Map<String, DateFieldHint> dateFieldHints) {
        return new IndexStatsResponse(true, documentCount, indexPath, schemaVersion, softwareVersion,
                buildTimestamp, dateFieldHints, null);
    }

    public static IndexStatsResponse error(final String errorMessage) {
        return new IndexStatsResponse(false, 0, null, 0, null, null, null, errorMessage);
    }
}
