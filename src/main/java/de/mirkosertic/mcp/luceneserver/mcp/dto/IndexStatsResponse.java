package de.mirkosertic.mcp.luceneserver.mcp.dto;

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
        String error
) {
    public static IndexStatsResponse success(final long documentCount, final String indexPath,
                                              final int schemaVersion, final String softwareVersion,
                                              final String buildTimestamp) {
        return new IndexStatsResponse(true, documentCount, indexPath, schemaVersion, softwareVersion, buildTimestamp, null);
    }

    public static IndexStatsResponse error(final String errorMessage) {
        return new IndexStatsResponse(false, 0, null, 0, null, null, errorMessage);
    }
}
