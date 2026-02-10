package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Metrics about the search operation and result set.
 */
public record SearchMetrics(
        long totalIndexedDocuments,
        long documentsMatchingQuery,
        long documentsAfterFilters,
        double filterReductionPercent,
        @Nullable Map<String, TermStatistics> termStatistics
) {
}
