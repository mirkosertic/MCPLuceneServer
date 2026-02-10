package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

/**
 * A single component contributing to the document's score.
 */
public record ScoreComponent(
        @Nullable String term,
        @Nullable String field,
        double contribution,
        double contributionPercent,
        @Nullable ScoreDetails details
) {
}
