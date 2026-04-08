package de.mirkosertic.mcp.luceneserver.index;

import de.mirkosertic.mcp.luceneserver.mcp.dto.VectorSearchDebug;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record SemanticSearchResult(
    LuceneIndexService.SearchResult searchResult,
    long embeddingDurationMs,
    float cosineCutoff,
    int rawCandidateCount,
    @Nullable List<VectorSearchDebug.VectorCandidateInfo> topCandidates
) {}
