package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Request DTO for the getTopTerms tool.
 */
public record GetTopTermsRequest(
        @Description("Field name to get top terms from (e.g. 'content', 'author', 'file_extension')")
        String field,

        @Nullable
        @Description("Maximum number of terms to return (default: 20, max: 100)")
        Integer limit
) {
    public static GetTopTermsRequest fromMap(final Map<String, Object> args) {
        return new GetTopTermsRequest(
                (String) args.get("field"),
                args.get("limit") != null ? ((Number) args.get("limit")).intValue() : null
        );
    }

    public int effectiveLimit() {
        return (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
    }
}
