package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
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
        @Nullable List<VectorSearchDebug.VectorCandidateInfo> knnDebugCandidates,
        @Nullable SemanticSearchResult searchResults
) {
    /**
     * Create a successful profile semantic search response from a debug result.
     */
    public static ProfileSemanticSearchResponse success(
            final LuceneIndexService.SemanticSearchWithDebugResult debugResult) {
        final SemanticSearchResult r = debugResult.searchResult();
        return new ProfileSemanticSearchResponse(
                true, null,
                r.embeddingDurationMs(),
                r.rawCandidateCount(),
                (int) r.totalHits(),
                r.cosineCutoff(),
                debugResult.knnCandidates(),
                r
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
