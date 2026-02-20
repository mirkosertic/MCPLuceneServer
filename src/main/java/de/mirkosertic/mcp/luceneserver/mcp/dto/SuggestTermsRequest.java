package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Request DTO for the suggestTerms tool.
 */
public record SuggestTermsRequest(
        @Description("Field name to suggest terms from (e.g. 'content', 'author', 'file_extension')")
        String field,

        @Description("Prefix to match terms against (e.g. 'ver' to find 'vertrag', 'version')")
        String prefix,

        @Nullable
        @Description("Maximum number of terms to return (default: 20, max: 100)")
        Integer limit
) {
    public static SuggestTermsRequest fromMap(final Map<String, Object> args) {
        return new SuggestTermsRequest(
                (String) args.get("field"),
                (String) args.get("prefix"),
                args.get("limit") != null ? ((Number) args.get("limit")).intValue() : null
        );
    }

    public int effectiveLimit() {
        return (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
    }
}
