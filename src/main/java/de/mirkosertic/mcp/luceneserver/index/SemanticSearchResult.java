package de.mirkosertic.mcp.luceneserver.index;

import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import java.util.List;

public record SemanticSearchResult(
    List<Passage> results,
    int totalHits,
    long embeddingDurationMs,
    float cosineCutoff
) {}
