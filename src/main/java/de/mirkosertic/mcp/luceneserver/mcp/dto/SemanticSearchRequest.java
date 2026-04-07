package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for the semantic (vector) search tool.
 * Results are ordered by cosine similarity; sort fields are not applicable.
 */
public record SemanticSearchRequest(
        @Nullable
        @Description("Search query (natural language text for semantic similarity search) or null for match-all")
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
        @Description("Minimum cosine similarity threshold (0.0–1.0, default: 0.70). Higher values return only closely matching results.")
        Float similarityThreshold
) {
    /**
     * Create a SemanticSearchRequest from a Map of arguments.
     */
    @SuppressWarnings("unchecked")
    public static SemanticSearchRequest fromMap(final Map<String, Object> args) {
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

        return new SemanticSearchRequest(
                (String) args.get("query"),
                filters,
                args.get("page") != null ? ((Number) args.get("page")).intValue() : null,
                args.get("pageSize") != null ? ((Number) args.get("pageSize")).intValue() : null,
                args.get("similarityThreshold") != null ? ((Number) args.get("similarityThreshold")).floatValue() : null
        );
    }

    /**
     * Get the effective similarity threshold with default.
     */
    public float effectiveSimilarityThreshold() {
        return similarityThreshold != null ? similarityThreshold : 0.70f;
    }

    /**
     * Validate the similarityThreshold parameter.
     * @return Error message if invalid, null if valid
     */
    public @Nullable String validateSimilarityThreshold() {
        if (similarityThreshold == null) {
            return null;  // Valid, will use default
        }
        if (similarityThreshold < 0.0f || similarityThreshold > 1.0f) {
            return "Invalid similarityThreshold: " + similarityThreshold + ". Must be between 0.0 and 1.0";
        }
        return null;  // Valid
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
