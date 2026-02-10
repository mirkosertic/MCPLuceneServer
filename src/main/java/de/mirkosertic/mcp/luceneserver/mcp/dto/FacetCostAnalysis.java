package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.Map;

/**
 * Analysis of faceting computation cost.
 */
public record FacetCostAnalysis(
        double facetingOverheadMs,
        double facetingOverheadPercent,
        Map<String, FacetDimensionCost> dimensions
) {
}
