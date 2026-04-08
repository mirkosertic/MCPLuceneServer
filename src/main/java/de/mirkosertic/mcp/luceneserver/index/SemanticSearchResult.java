package de.mirkosertic.mcp.luceneserver.index;

import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;

import java.util.List;

/**
 * Result of a semantic (KNN vector) search.
 *
 * <p>Extends the concept of a regular search result with semantic-search-specific
 * metrics: embedding latency, the effective cosine similarity cutoff, and the
 * raw KNN candidate count before threshold filtering.</p>
 */
public record SemanticSearchResult(
        List<SearchDocument> documents,
        long totalHits,
        int page,
        int pageSize,
        long embeddingDurationMs,
        float cosineCutoff,
        int rawCandidateCount
) {
    public int totalPages() {
        return (int) Math.ceil((double) totalHits / pageSize);
    }

    public boolean hasNextPage() {
        return page < totalPages() - 1;
    }

    public boolean hasPreviousPage() {
        return page > 0;
    }
}
