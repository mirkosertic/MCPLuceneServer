package de.mirkosertic.mcp.luceneserver.mcp;

import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchAction;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless utility that generates HATEOAS-style {@code _actions} for search responses.
 *
 * <p>All methods are pure functions with no Lucene dependency — safe to use from any layer.</p>
 */
public class SearchActionGenerator {

    /** Maximum number of drill-down actions per facet field. */
    private static final int MAX_DRILLDOWN_VALUES = 2;

    /**
     * Generate response-level actions for pagination and drill-down.
     *
     * @param toolName        the MCP tool name to embed in action parameters (e.g. {@code "simpleSearch"})
     * @param state           current search state (query, filters, page, pageSize)
     * @param facets          facet values returned by the search (field → ordered list of value/count pairs)
     * @param hasNextPage     whether a next page exists
     * @param hasPreviousPage whether a previous page exists
     * @return list of actions (never null, may be empty)
     */
    public List<SearchAction> generateResponseActions(
            final String toolName,
            final SearchState state,
            final Map<String, List<SearchResponse.FacetValue>> facets,
            final boolean hasNextPage,
            final boolean hasPreviousPage) {

        final List<SearchAction> actions = new ArrayList<>();

        if (hasPreviousPage) {
            actions.add(new SearchAction(
                    "prevPage",
                    toolName,
                    buildPageParams(state, state.page() - 1),
                    null
            ));
        }

        if (hasNextPage) {
            actions.add(new SearchAction(
                    "nextPage",
                    toolName,
                    buildPageParams(state, state.page() + 1),
                    null
            ));
        }

        if (facets != null) {
            for (final Map.Entry<String, List<SearchResponse.FacetValue>> entry : facets.entrySet()) {
                final String field = entry.getKey();
                final List<SearchResponse.FacetValue> values = entry.getValue();
                int added = 0;
                for (final SearchResponse.FacetValue fv : values) {
                    if (added >= MAX_DRILLDOWN_VALUES) {
                        break;
                    }
                    if (isAlreadyActive(state.filters(), field, fv.value())) {
                        continue;
                    }
                    final List<Map<String, Object>> filtersWithNew = buildFiltersWithNew(state.filters(), field, fv.value());
                    final Map<String, Object> params = new HashMap<>();
                    params.put("query", state.query());
                    params.put("filters", filtersWithNew);
                    params.put("page", 0);
                    params.put("pageSize", state.pageSize());
                    actions.add(new SearchAction("drillDown", toolName, params, fv.count()));
                    added++;
                }
            }
        }

        return actions;
    }

    /**
     * Generate document-level actions (fetch full content).
     *
     * @param toolName unused — always uses {@code "getDocumentDetails"} regardless of the search tool
     * @param filePath absolute path of the indexed document
     * @return list containing a single {@code fetchContent} action
     */
    public List<SearchAction> generateDocumentActions(
            @SuppressWarnings("unused") final String toolName,
            final String filePath) {

        final Map<String, Object> params = new HashMap<>();
        params.put("filePath", filePath);
        return List.of(new SearchAction("fetchContent", "getDocumentDetails", params, null));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> buildPageParams(final SearchState state, final int targetPage) {
        final Map<String, Object> params = new HashMap<>();
        params.put("query", state.query());
        params.put("filters", filtersToMaps(state.filters()));
        params.put("page", targetPage);
        params.put("pageSize", state.pageSize());
        return params;
    }

    private boolean isAlreadyActive(final List<SearchFilter> filters, final String field, final String value) {
        for (final SearchFilter f : filters) {
            if (field.equals(f.field()) && "eq".equals(f.effectiveOperator()) && value.equals(f.value())) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildFiltersWithNew(
            final List<SearchFilter> existing, final String field, final String value) {
        final List<Map<String, Object>> result = new ArrayList<>(filtersToMaps(existing));
        final Map<String, Object> newFilter = new HashMap<>();
        newFilter.put("field", field);
        newFilter.put("operator", "eq");
        newFilter.put("value", value);
        result.add(newFilter);
        return result;
    }

    private List<Map<String, Object>> filtersToMaps(final List<SearchFilter> filters) {
        final List<Map<String, Object>> result = new ArrayList<>(filters.size());
        for (final SearchFilter f : filters) {
            final Map<String, Object> m = new HashMap<>();
            m.put("field", f.field());
            m.put("operator", f.effectiveOperator());
            if (f.value() != null) {
                m.put("value", f.value());
            }
            if (f.values() != null) {
                m.put("values", f.values());
            }
            if (f.from() != null) {
                m.put("from", f.from());
            }
            if (f.to() != null) {
                m.put("to", f.to());
            }
            result.add(m);
        }
        return result;
    }
}
