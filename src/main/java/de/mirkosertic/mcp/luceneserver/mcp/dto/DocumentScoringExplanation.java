package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Detailed scoring explanation for a single document.
 */
public record DocumentScoringExplanation(
        String filePath,
        int rank,
        double score,
        ScoringBreakdown scoringBreakdown,
        List<String> matchedTerms
) {
}
