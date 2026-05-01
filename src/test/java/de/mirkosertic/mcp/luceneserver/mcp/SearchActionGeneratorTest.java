package de.mirkosertic.mcp.luceneserver.mcp;

import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchAction;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchActionGeneratorTest {

    private SearchActionGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SearchActionGenerator();
    }

    // -------------------------------------------------------------------------
    // nextPage
    // -------------------------------------------------------------------------

    @Test
    void nextPageActionGeneratedWhenHasNextPageTrue() {
        final SearchState state = new SearchState("foo", List.of(), 0, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), true, false);

        assertThat(actions).anyMatch(a -> "nextPage".equals(a.type()));
    }

    @Test
    void nextPageActionNotGeneratedWhenHasNextPageFalse() {
        final SearchState state = new SearchState("foo", List.of(), 0, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), false, false);

        assertThat(actions).noneMatch(a -> "nextPage".equals(a.type()));
    }

    @Test
    void nextPageParametersContainIncrementedPage() {
        final SearchState state = new SearchState("bar", List.of(), 2, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), true, false);

        final SearchAction nextPage = actions.stream()
                .filter(a -> "nextPage".equals(a.type()))
                .findFirst()
                .orElseThrow();
        assertThat(nextPage.parameters().get("page")).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // prevPage
    // -------------------------------------------------------------------------

    @Test
    void prevPageActionGeneratedWhenHasPreviousPageTrue() {
        final SearchState state = new SearchState("foo", List.of(), 1, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), false, true);

        assertThat(actions).anyMatch(a -> "prevPage".equals(a.type()));
    }

    @Test
    void prevPageActionNotGeneratedWhenHasPreviousPageFalse() {
        final SearchState state = new SearchState("foo", List.of(), 0, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), false, false);

        assertThat(actions).noneMatch(a -> "prevPage".equals(a.type()));
    }

    @Test
    void prevPageParametersContainDecrementedPage() {
        final SearchState state = new SearchState("baz", List.of(), 3, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), false, true);

        final SearchAction prevPage = actions.stream()
                .filter(a -> "prevPage".equals(a.type()))
                .findFirst()
                .orElseThrow();
        assertThat(prevPage.parameters().get("page")).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // drillDown
    // -------------------------------------------------------------------------

    @Test
    void drillDownParametersContainExistingFiltersPlusNewFilter() {
        final SearchFilter existing = new SearchFilter("language", "eq", "de", null, null, null, null);
        final SearchState state = new SearchState("test", List.of(existing), 0, 10);

        final List<SearchResponse.FacetValue> facetValues = List.of(
                new SearchResponse.FacetValue("pdf", 5L)
        );
        final Map<String, List<SearchResponse.FacetValue>> facets = Map.of("file_extension", facetValues);

        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, facets, false, false);

        final SearchAction drillDown = actions.stream()
                .filter(a -> "drillDown".equals(a.type()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> filters = (List<Map<String, Object>>) drillDown.parameters().get("filters");
        assertThat(filters).hasSize(2);
        assertThat(filters.get(0).get("field")).isEqualTo("language");
        assertThat(filters.get(1).get("field")).isEqualTo("file_extension");
        assertThat(filters.get(1).get("value")).isEqualTo("pdf");
    }

    @Test
    void drillDownNotGeneratedForAlreadyActiveFilter() {
        final SearchFilter existing = new SearchFilter("language", "eq", "de", null, null, null, null);
        final SearchState state = new SearchState("test", List.of(existing), 0, 10);

        // Facet has the same field+value that's already active
        final List<SearchResponse.FacetValue> facetValues = List.of(
                new SearchResponse.FacetValue("de", 10L)
        );
        final Map<String, List<SearchResponse.FacetValue>> facets = Map.of("language", facetValues);

        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, facets, false, false);

        assertThat(actions).noneMatch(a -> "drillDown".equals(a.type()));
    }

    @Test
    void drillDownLimitedToTopTwoValuesPerFacet() {
        final SearchState state = new SearchState("test", List.of(), 0, 10);

        final List<SearchResponse.FacetValue> facetValues = List.of(
                new SearchResponse.FacetValue("de", 20L),
                new SearchResponse.FacetValue("en", 10L),
                new SearchResponse.FacetValue("fr", 5L)
        );
        final Map<String, List<SearchResponse.FacetValue>> facets = Map.of("language", facetValues);

        final List<SearchAction> actions = generator.generateResponseActions(
                "simpleSearch", state, facets, false, false);

        final long drillDownCount = actions.stream().filter(a -> "drillDown".equals(a.type())).count();
        assertThat(drillDownCount).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // fetchContent
    // -------------------------------------------------------------------------

    @Test
    void fetchContentContainsCorrectFilePath() {
        final List<SearchAction> actions = generator.generateDocumentActions(
                "simpleSearch", "/some/path/document.pdf");

        assertThat(actions).hasSize(1);
        final SearchAction action = actions.get(0);
        assertThat(action.type()).isEqualTo("fetchContent");
        assertThat(action.parameters().get("filePath")).isEqualTo("/some/path/document.pdf");
    }

    @Test
    void fetchContentAlwaysUsesGetDocumentDetails() {
        final List<SearchAction> actions = generator.generateDocumentActions(
                "extendedSearch", "/some/path/document.pdf");

        assertThat(actions.get(0).tool()).isEqualTo("getDocumentDetails");
    }

    // -------------------------------------------------------------------------
    // Tool name propagation
    // -------------------------------------------------------------------------

    @Test
    void toolNameReflectedCorrectlyInResponseActions() {
        final SearchState state = new SearchState("hello", List.of(), 0, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "extendedSearch", state, Map.of(), true, true);

        assertThat(actions).allMatch(a -> "extendedSearch".equals(a.tool()));
    }

    @Test
    void generateDocumentActionsAlwaysUsesGetDocumentDetailsRegardlessOfToolName() {
        final List<SearchAction> actionsSimple = generator.generateDocumentActions("simpleSearch", "/doc.pdf");
        final List<SearchAction> actionsExtended = generator.generateDocumentActions("extendedSearch", "/doc.pdf");
        final List<SearchAction> actionsSemantic = generator.generateDocumentActions("semanticSearch", "/doc.pdf");

        assertThat(actionsSimple.get(0).tool()).isEqualTo("getDocumentDetails");
        assertThat(actionsExtended.get(0).tool()).isEqualTo("getDocumentDetails");
        assertThat(actionsSemantic.get(0).tool()).isEqualTo("getDocumentDetails");
    }
}
