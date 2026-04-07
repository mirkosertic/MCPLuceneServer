package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request DTO for the extended search tool (full Lucene query syntax).
 */
public record ExtendedSearchRequest(
        @Nullable
        @Description("Search query (full Lucene syntax) or null for match-all")
        String query,

        @Nullable
        @Description("Filters array for field-level filtering")
        List<SearchFilter> filters,

        @Nullable
        @Description("Page number (0-based, default: 0)")
        Integer page,

        @Nullable
        @Description("Results per page (default: 10, max: 100)")
        Integer pageSize,

        @Nullable
        @Description("Sort field: _score, modified_date, created_date, or file_size (default: _score)")
        String sortBy,

        @Nullable
        @Description("Sort order: asc or desc (default: desc)")
        String sortOrder
) {
    /**
     * Create an ExtendedSearchRequest from a Map of arguments.
     */
    @SuppressWarnings("unchecked")
    public static ExtendedSearchRequest fromMap(final Map<String, Object> args) {
        final List<SearchFilter> filters;
        if (args.get("filters") instanceof final List<?> rawFilters) {
            filters = new ArrayList<>(rawFilters.size());
            for (final Object item : rawFilters) {
                if (item instanceof final Map<?, ?> filterMap) {
                    filters.add(SearchFilter.fromMap((Map<String, Object>) filterMap));
                }
            }
        } else {
            filters = null;
        }

        return new ExtendedSearchRequest(
                (String) args.get("query"),
                filters,
                args.get("page") != null ? ((Number) args.get("page")).intValue() : null,
                args.get("pageSize") != null ? ((Number) args.get("pageSize")).intValue() : null,
                (String) args.get("sortBy"),
                (String) args.get("sortOrder")
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

    /**
     * Get the effective sort field with default.
     */
    public String effectiveSortBy() {
        return sortBy != null && !sortBy.isBlank() ? sortBy : "_score";
    }

    /**
     * Get the effective sort order with default.
     * Default: desc for all fields (higher scores first for relevance, most recent/largest for metadata).
     */
    public String effectiveSortOrder() {
        if (sortOrder != null && !sortOrder.isBlank()) {
            return sortOrder;
        }
        return "desc";  // desc for all fields by default
    }

    /**
     * Validate the sortBy parameter.
     * @return Error message if invalid, null if valid
     */
    public static @Nullable String validateSortBy(final @Nullable String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return null;  // Valid, will use default
        }

        final Set<String> validFields = Set.of(
                "_score", "modified_date", "created_date", "file_size"
        );

        if (!validFields.contains(sortBy)) {
            return "Invalid sortBy field: " + sortBy + ". Valid fields: " + validFields;
        }

        return null;  // Valid
    }

    /**
     * Validate the sortOrder parameter.
     * @return Error message if invalid, null if valid
     */
    public static @Nullable String validateSortOrder(final @Nullable String sortOrder) {
        if (sortOrder == null || sortOrder.isBlank()) {
            return null;  // Valid, will use default
        }

        if (!"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            return "Invalid sortOrder: " + sortOrder + ". Valid values: asc, desc";
        }

        return null;  // Valid
    }
}
