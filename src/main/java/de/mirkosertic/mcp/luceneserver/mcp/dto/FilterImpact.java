package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Impact of a single filter on search results.
 */
public record FilterImpact(
        SearchFilter filter,
        long hitsBeforeFilter,
        long hitsAfterFilter,
        long documentsRemoved,
        double reductionPercent,
        String selectivity,           // "low", "medium", "high", "very high"
        double executionTimeMs
) {
}
