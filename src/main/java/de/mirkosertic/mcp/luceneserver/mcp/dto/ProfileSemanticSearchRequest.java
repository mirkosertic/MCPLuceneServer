package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for the profileSemanticSearch tool.
 */
public record ProfileSemanticSearchRequest(
        @Nullable
        @Description("Search query (natural language text for semantic similarity search) or null for match-all")
        String query,

        @Nullable
        @Description("Filters array for field-level filtering")
        List<SearchFilter> filters,

        @Nullable
        @Description("Minimum cosine similarity threshold (0.0–1.0, default: 0.70). Higher values return only closely matching results.")
        Float similarityThreshold
) {
    /**
     * Create a ProfileSemanticSearchRequest from a Map of arguments.
     */
    @SuppressWarnings("unchecked")
    public static ProfileSemanticSearchRequest fromMap(final Map<String, Object> args) {
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

        return new ProfileSemanticSearchRequest(
                (String) args.get("query"),
                filters,
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
     * Get the effective filters list, returning an empty list if filters is null.
     */
    public List<SearchFilter> effectiveFilters() {
        return filters != null ? filters : List.of();
    }
}
