package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Cost information for a single facet dimension.
 */
public record FacetDimensionCost(
        String dimension,
        int uniqueValues,
        int totalCount,
        double computationTimeMs
) {
}
