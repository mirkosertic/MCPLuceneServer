package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the search tool.
 *
 * <p>Each document in {@link #documents()} contains a {@code passages} array instead of
 * a single {@code snippet} string.  Every passage carries quality metadata
 * ({@code score}, {@code matchedTerms}, {@code termCoverage}, {@code position})
 * intended to help an LLM decide which passages are most relevant without
 * re-ranking.  See {@link Passage} for the full field contract.</p>
 */
public record SearchResponse(
        boolean success,
        List<SearchDocument> documents,
        long totalHits,
        int page,
        int pageSize,
        int totalPages,
        boolean hasNextPage,
        boolean hasPreviousPage,
        Map<String, List<FacetValue>> facets,
        long searchTimeMs,
        String error
) {
    /**
     * Create a successful search response.
     */
    public static SearchResponse success(
            final List<SearchDocument> documents,
            final long totalHits,
            final int page,
            final int pageSize,
            final int totalPages,
            final boolean hasNextPage,
            final boolean hasPreviousPage,
            final Map<String, List<FacetValue>> facets,
            final long searchTimeMs) {
        return new SearchResponse(
                true, documents, totalHits, page, pageSize,
                totalPages, hasNextPage, hasPreviousPage, facets, searchTimeMs, null
        );
    }

    /**
     * Create an error response.
     */
    public static SearchResponse error(final String errorMessage) {
        return new SearchResponse(
                false, null, 0, 0, 0, 0, false, false, null, 0, errorMessage
        );
    }

    /**
     * Facet value with label and count.
     */
    public record FacetValue(String value, long count) {
    }
}
