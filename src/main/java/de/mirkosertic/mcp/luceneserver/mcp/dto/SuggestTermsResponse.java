package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Response DTO for the suggestTerms tool.
 */
public record SuggestTermsResponse(
        boolean success,
        String field,
        String prefix,
        List<TermFrequency> terms,
        int totalTermsMatched,
        String error
) {
    public record TermFrequency(String term, int docFreq) {
    }

    public static SuggestTermsResponse success(final String field, final String prefix,
                                                 final List<TermFrequency> terms, final int totalTermsMatched) {
        return new SuggestTermsResponse(true, field, prefix, terms, totalTermsMatched, null);
    }

    public static SuggestTermsResponse error(final String errorMessage) {
        return new SuggestTermsResponse(false, null, null, null, 0, errorMessage);
    }
}
