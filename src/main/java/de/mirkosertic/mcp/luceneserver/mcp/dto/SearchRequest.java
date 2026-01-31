package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for the search tool.
 */
public record SearchRequest(
        @Description("The search query using Lucene query syntax")
        String query,

        @Nullable
        @Description("Optional field name to filter results. Use together with filterValue.")
        String filterField,

        @Nullable
        @Description("Optional value for the filter field. Use together with filterField.")
        String filterValue,

        @Nullable
        @Description("Page number (0-based). Default is 0.")
        Integer page,

        @Nullable
        @Description("Number of results per page. Default is 10, maximum is 100.")
        Integer pageSize
) {
    /**
     * Create a SearchRequest from a Map of arguments.
     */
    public static SearchRequest fromMap(final java.util.Map<String, Object> args) {
        return new SearchRequest(
                (String) args.get("query"),
                (String) args.get("filterField"),
                (String) args.get("filterValue"),
                args.get("page") != null ? ((Number) args.get("page")).intValue() : null,
                args.get("pageSize") != null ? ((Number) args.get("pageSize")).intValue() : null
        );
    }

    /**
     * Get the effective page number with default.
     */
    public int effectivePage() {
        return (page != null && page >= 0) ? page : 0;
    }

    /**
     * Get the effective page size with default and constraint.
     */
    public int effectivePageSize() {
        return (pageSize != null && pageSize > 0) ? Math.min(pageSize, 100) : 10;
    }
}
