package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Debug information for vector search collected during profileQuery analysis.
 */
public record VectorSearchDebug(
        boolean vectorSearchAvailable,
        boolean vectorSearchEnabled,
        long embeddingDurationMs,
        int rawCandidateCount,
        int filteredCandidateCount,
        float cosineCutoff,
        @Nullable List<VectorCandidateInfo> topCandidates
) {

    /**
     * Information about a single vector search candidate.
     */
    public record VectorCandidateInfo(
            String filePath,
            int chunkIndex,
            @Nullable String chunkText,
            float luceneScore,
            float cosineScore,
            boolean passedThreshold,
            int vectorRank,
            float rrfContribution
    ) {}
}
