package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Analysis of how filters affect search results.
 */
public record FilterImpactAnalysis(
        long baselineHits,
        long finalHits,
        List<FilterImpact> filterImpacts,
        double totalExecutionTimeMs
) {
}
