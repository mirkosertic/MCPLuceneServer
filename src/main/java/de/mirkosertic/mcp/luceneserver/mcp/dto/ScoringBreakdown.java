package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Breakdown of how a document's score was calculated.
 */
public record ScoringBreakdown(
        double totalScore,
        List<ScoreComponent> components,
        String summary                  // "Score dominated by term 'contract' (60.8%)"
) {
}
