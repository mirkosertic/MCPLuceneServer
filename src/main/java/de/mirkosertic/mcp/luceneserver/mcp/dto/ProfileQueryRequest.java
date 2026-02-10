package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for the profileQuery tool.
 */
public record ProfileQueryRequest(
        @Nullable
        @Description("Search query (Lucene syntax) or null for match-all")
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
        @Description("Enable filter impact analysis (expensive, requires N+1 queries)")
        Boolean analyzeFilterImpact,

        @Nullable
        @Description("Enable document scoring explanations (expensive)")
        Boolean analyzeDocumentScoring,

        @Nullable
        @Description("Enable facet cost analysis (expensive)")
        Boolean analyzeFacetCost,

        @Nullable
        @Description("Max documents to explain when analyzeDocumentScoring=true (default: 5, max: 10)")
        Integer maxDocExplanations
) {
    /**
     * Create a ProfileQueryRequest from a Map of arguments.
     */
    @SuppressWarnings("unchecked")
    public static ProfileQueryRequest fromMap(final Map<String, Object> args) {
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

        return new ProfileQueryRequest(
                (String) args.get("query"),
                filters,
                args.get("page") != null ? ((Number) args.get("page")).intValue() : null,
                args.get("pageSize") != null ? ((Number) args.get("pageSize")).intValue() : null,
                (Boolean) args.get("analyzeFilterImpact"),
                (Boolean) args.get("analyzeDocumentScoring"),
                (Boolean) args.get("analyzeFacetCost"),
                args.get("maxDocExplanations") != null ? ((Number) args.get("maxDocExplanations")).intValue() : null
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
     * Should analyze filter impact.
     */
    public boolean effectiveAnalyzeFilterImpact() {
        return analyzeFilterImpact != null && analyzeFilterImpact;
    }

    /**
     * Should analyze document scoring.
     */
    public boolean effectiveAnalyzeDocumentScoring() {
        return analyzeDocumentScoring != null && analyzeDocumentScoring;
    }

    /**
     * Should analyze facet cost.
     */
    public boolean effectiveAnalyzeFacetCost() {
        return analyzeFacetCost != null && analyzeFacetCost;
    }

    /**
     * Get the effective max document explanations with default and constraint.
     */
    public int effectiveMaxDocExplanations() {
        return (maxDocExplanations != null && maxDocExplanations > 0) ? Math.min(maxDocExplanations, 10) : 5;
    }
}
