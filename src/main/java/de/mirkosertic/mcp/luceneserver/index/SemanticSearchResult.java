package de.mirkosertic.mcp.luceneserver.index;

import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import de.mirkosertic.mcp.luceneserver.mcp.dto.VectorSearchDebug;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record SemanticSearchResult(
    List<Passage> results,
    int totalHits,
    long embeddingDurationMs,
    float cosineCutoff,
    int rawCandidateCount,
    @Nullable List<VectorSearchDebug.VectorCandidateInfo> topCandidates
) {}
