package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.List;

/**
 * A single highlighted passage extracted from a search result document.
 *
 * <p>Passages are returned in descending relevance order (best passage first).
 * Each passage carries enough metadata for an LLM to decide which passages
 * are most useful without having to re-rank them.</p>
 */
public record Passage(
        @Description("The highlighted passage text. Query terms that matched are wrapped in <em>...</em> tags.")
        String text,

        @Description("Passage relevance score normalised to the 0.0-1.0 range. " +
                "Derived from the highlighter's passage ordering: the first (best) passage receives the highest score.")
        double score,

        @Description("Query terms that were found in this passage, extracted from the text between <em> tags. " +
                "Useful for understanding which parts of a multi-term query this passage satisfies.")
        List<String> matchedTerms,

        @Description("Fraction of the total query terms that appear in this passage (0.0-1.0). " +
                "A value of 1.0 means every query term is present; useful for filtering passages that cover the full query.")
        double termCoverage,

        @Description("Approximate position of this passage within the source document " +
                "(0.0 = very start, 1.0 = very end). Helpful for understanding document structure " +
                "and for citations that need to reference a location.")
        double position
) {
}
