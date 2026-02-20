package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Response DTO for the getTopTerms tool.
 */
public record GetTopTermsResponse(
        boolean success,
        String field,
        List<TermFrequency> terms,
        long uniqueTermCount,
        String warning,
        String error
) {
    public record TermFrequency(String term, int docFreq) {
    }

    public static GetTopTermsResponse success(final String field, final List<TermFrequency> terms,
                                                final long uniqueTermCount, final String warning) {
        return new GetTopTermsResponse(true, field, terms, uniqueTermCount, warning, null);
    }

    public static GetTopTermsResponse error(final String errorMessage) {
        return new GetTopTermsResponse(false, null, null, 0, null, errorMessage);
    }
}
