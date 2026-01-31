package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the getIndexStats tool.
 */
public record IndexStatsResponse(
        boolean success,
        long documentCount,
        String indexPath,
        String error
) {
    public static IndexStatsResponse success(final long documentCount, final String indexPath) {
        return new IndexStatsResponse(true, documentCount, indexPath, null);
    }

    public static IndexStatsResponse error(final String errorMessage) {
        return new IndexStatsResponse(false, 0, null, errorMessage);
    }
}
