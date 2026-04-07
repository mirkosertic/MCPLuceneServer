package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.index.SemanticSearchResult;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Response DTO for the profileSemanticSearch tool.
 */
public record ProfileSemanticSearchResponse(
        boolean success,
        @Nullable String error,
        long embeddingDurationMs,
        int rawCandidateCount,
        int filteredCandidateCount,
        float cosineCutoff,
        @Nullable List<VectorSearchDebug.VectorCandidateInfo> topCandidates,
        @Nullable SemanticSearchResult searchResults
) {
    /**
     * Create a successful profile semantic search response.
     */
    public static ProfileSemanticSearchResponse success(
            final long embeddingDurationMs,
            final int rawCandidateCount,
            final int filteredCandidateCount,
            final float cosineCutoff,
            final @Nullable List<VectorSearchDebug.VectorCandidateInfo> topCandidates,
            final @Nullable SemanticSearchResult searchResults) {
        return new ProfileSemanticSearchResponse(
                true, null, embeddingDurationMs, rawCandidateCount,
                filteredCandidateCount, cosineCutoff, topCandidates, searchResults
        );
    }

    /**
     * Create an error response.
     */
    public static ProfileSemanticSearchResponse error(final String errorMessage) {
        return new ProfileSemanticSearchResponse(
                false, errorMessage, 0, 0, 0, 0.0f, null, null
        );
    }
}
