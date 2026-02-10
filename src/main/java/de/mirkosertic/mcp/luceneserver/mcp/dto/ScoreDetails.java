package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Detailed information about how a score component was calculated.
 */
public record ScoreDetails(
        double idf,                       // inverse document frequency
        double tf,                        // term frequency (normalized)
        int termFrequency,                // raw term frequency in document
        int documentLength,               // length of this document
        int averageDocumentLength,        // average length across all documents
        String explanation                // Human-readable explanation
) {
}
