package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for the search tool.
 */
public record SearchRequest(
        @Nullable
        @Description("The search query using Lucene query syntax. Can be null or '*' to match all documents (useful with filters).")
        String query,

        @Nullable
        @Description("Structured filters array. Each filter specifies a field, operator, and value(s).")
        List<SearchFilter> filters,

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
    @SuppressWarnings("unchecked")
    public static SearchRequest fromMap(final Map<String, Object> args) {
        final List<SearchFilter> filters;
        if (args.get("filters") instanceof List<?> rawFilters) {
            filters = new ArrayList<>(rawFilters.size());
            for (final Object item : rawFilters) {
                if (item instanceof Map<?, ?> filterMap) {
                    filters.add(SearchFilter.fromMap((Map<String, Object>) filterMap));
                }
            }
        } else {
            filters = null;
        }

        return new SearchRequest(
                (String) args.get("query"),
                filters,
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

    /**
     * Get the effective query string — returns null for blank or "*" queries (signals MatchAllDocsQuery).
     */
    public @Nullable String effectiveQuery() {
        if (query == null || query.isBlank() || "*".equals(query.trim())) {
            return null;
        }
        return query;
    }

    /**
     * Get the effective filters list, returning an empty list if filters is null.
     */
    public List<SearchFilter> effectiveFilters() {
        return filters != null ? filters : List.of();
    }
}
