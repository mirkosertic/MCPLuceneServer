package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Statistics for a specific search term.
 */
public record TermStatistics(
        String term,
        long documentFrequency,       // number of documents containing this term
        long totalTermFrequency,      // total occurrences across all documents
        double idf,                   // inverse document frequency
        String rarity                 // "very common", "common", "uncommon", "rare"
) {
}
