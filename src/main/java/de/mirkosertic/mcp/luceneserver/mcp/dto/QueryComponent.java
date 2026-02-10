package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

/**
 * A component of the parsed query structure.
 */
public record QueryComponent(
        String type,                // "TermQuery", "WildcardQuery", "BooleanQuery", "PhraseQuery", etc.
        @Nullable String field,
        @Nullable String value,
        @Nullable String occur,     // "MUST", "SHOULD", "FILTER", "MUST_NOT" for boolean clauses
        long estimatedCost,         // from Lucene's cost() API
        String costDescription      // "~450 documents to examine"
) {
}
