package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Response DTO for the profileQuery tool.
 */
public record ProfileQueryResponse(
        boolean success,
        @Nullable QueryAnalysis queryAnalysis,
        @Nullable SearchMetrics searchMetrics,
        @Nullable FilterImpactAnalysis filterImpact,
        @Nullable List<DocumentScoringExplanation> documentExplanations,
        @Nullable FacetCostAnalysis facetCost,
        @Nullable List<String> recommendations,
        @Nullable VectorSearchDebug vectorSearchDebug,
        @Nullable String error
) {
    /**
     * Create a successful profile query response.
     */
    public static ProfileQueryResponse success(
            final QueryAnalysis queryAnalysis,
            final SearchMetrics searchMetrics,
            final @Nullable FilterImpactAnalysis filterImpact,
            final @Nullable List<DocumentScoringExplanation> documentExplanations,
            final @Nullable FacetCostAnalysis facetCost,
            final List<String> recommendations,
            final @Nullable VectorSearchDebug vectorSearchDebug) {
        return new ProfileQueryResponse(
                true, queryAnalysis, searchMetrics, filterImpact,
                documentExplanations, facetCost, recommendations, vectorSearchDebug, null
        );
    }

    /**
     * Create an error response.
     */
    public static ProfileQueryResponse error(final String errorMessage) {
        return new ProfileQueryResponse(
                false, null, null, null, null, null, null, null, errorMessage
        );
    }
}
