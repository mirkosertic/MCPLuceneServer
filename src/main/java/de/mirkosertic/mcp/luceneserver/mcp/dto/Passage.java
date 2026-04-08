package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A single highlighted passage extracted from a search result document.
 *
 * <p>Passages are returned in descending relevance order (best passage first).
 * Each passage carries enough metadata for an LLM to decide which passages
 * are most useful without having to re-rank them.</p>
 */
public record Passage(
        @Description("The highlighted passage text. Query terms that matched are wrapped in **markdown bold** syntax.")
        String text,

        @Description("Passage relevance score in the 0.0–1.0 range. For keyword passages: normalised rank from the BM25 highlighter (1.0 = best passage). For semantic passages: cosine similarity between this chunk and the query embedding.")
        double score,

        @Description("Query terms that were found in this passage, extracted from the text between markdown bold markers (**). " +
                "Useful for understanding which parts of a multi-term query this passage satisfies.")
        List<String> matchedTerms,

        @Description("Fraction of the total query terms that appear in this passage (0.0-1.0). " +
                "A value of 1.0 means every query term is present; useful for filtering passages that cover the full query.")
        double termCoverage,

        @Description("Normalised position within the source document (0.0 = first chunk / start, 1.0 = last chunk / end). For keyword passages computed from byte offset; for semantic passages stored at index time.")
        double position,

        @Description("'keyword' = BM25 term-match passage extracted by the highlighter; 'semantic' = chunk retrieved by KNN vector similarity.")
        String source,

        @Description("Zero-based index of the embedding chunk within the source document. " +
                     "Present only for semantic passages; null for keyword passages.")
        @Nullable Integer chunkIndex
) {
}
