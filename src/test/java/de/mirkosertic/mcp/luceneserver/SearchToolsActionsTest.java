package de.mirkosertic.mcp.luceneserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.mcp.SearchActionGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ActiveFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchAction;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchState;
import de.mirkosertic.mcp.luceneserver.tools.SearchTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that the SearchActionGenerator produces correct _search and _actions
 * fields that would be included in search responses.
 *
 * <p>These tests are complementary to SearchActionGeneratorTest and verify higher-level
 * scenarios including response/document action composition and JSON serialization.</p>
 */
@DisplayName("SearchTools _actions integration tests")
class SearchToolsActionsTest {

    private SearchActionGenerator generator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        generator = new SearchActionGenerator();
        objectMapper = new ObjectMapper();
    }

    private SearchDocument makeDoc(final String filePath) {
        return SearchDocument.builder()
                .score(1.0)
                .filePath(filePath)
                .fileName("doc.txt")
                .title("Test Doc")
                .passages(List.of(new Passage("some text", 0.9, List.of("text"), 0.5, 0, "keyword", null)))
                .build();
    }

    @Test
    @DisplayName("SearchDocument built with fetchContent action contains correct filePath")
    void searchDocumentWithFetchContentActionContainsCorrectFilePath() {
        final SearchDocument doc = makeDoc("/reports/annual-report.pdf");
        final List<SearchAction> actions = generator.generateDocumentActions("simpleSearch", doc.filePath());
        final SearchDocument enriched = SearchDocument.builder()
                .score(doc.score())
                .filePath(doc.filePath())
                .fileName(doc.fileName())
                .title(doc.title())
                .passages(doc.passages())
                .actions(actions)
                .build();

        assertThat(enriched.actions()).isNotNull().isNotEmpty();
        final SearchAction action = enriched.actions().get(0);
        assertThat(action.type()).isEqualTo("fetchContent");
        assertThat(action.tool()).isEqualTo("getDocumentDetails");
        assertThat(action.parameters().get("filePath")).isEqualTo("/reports/annual-report.pdf");
    }

    @Test
    @DisplayName("SearchResponse built with _actions and _search contains non-null fields")
    void searchResponseContainsActionsAndSearchState() {
        final SearchState state = new SearchState("contract", List.of(), 0, 10);
        final SearchDocument doc = makeDoc("/docs/contract.pdf");
        final List<SearchAction> responseActions = generator.generateResponseActions(
                "simpleSearch", state, Map.of(), false, false);
        final List<SearchDocument> enrichedDocs = List.of(
                SearchDocument.builder()
                        .score(doc.score())
                        .filePath(doc.filePath())
                        .fileName(doc.fileName())
                        .passages(doc.passages())
                        .actions(generator.generateDocumentActions("simpleSearch", doc.filePath()))
                        .build()
        );

        final SearchResponse response = SearchResponse.success(
                enrichedDocs, 1L, 0, 10, 1, false, false,
                Map.of(), List.of(), 5L, state, responseActions);

        assertThat(response.search()).isNotNull();
        assertThat(response.search().query()).isEqualTo("contract");
        assertThat(response.actions()).isNotNull();
        assertThat(response.documents().get(0).actions()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("SearchResponse _actions JSON serialization contains _actions and _search keys")
    void searchResponseSerializesToJsonWithActionsAndSearchKeys() throws Exception {
        final SearchState state = new SearchState("hello", List.of(), 0, 10);
        final List<SearchAction> responseActions = generator.generateResponseActions(
                "extendedSearch", state, Map.of(), true, false);

        final SearchResponse response = SearchResponse.success(
                List.of(), 20L, 0, 10, 2, true, false,
                Map.of(), List.of(), 8L, state, responseActions);

        final String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("_actions");
        assertThat(json).contains("_search");
        assertThat(json).contains("nextPage");
    }

    @Test
    @DisplayName("SearchDocument _actions JSON serialization contains fetchContent")
    void searchDocumentSerializesToJsonWithActionsKey() throws Exception {
        final SearchDocument doc = makeDoc("/path/to/doc.txt");
        final SearchDocument enriched = SearchDocument.builder()
                .score(doc.score())
                .filePath(doc.filePath())
                .fileName(doc.fileName())
                .passages(doc.passages())
                .actions(generator.generateDocumentActions("simpleSearch", doc.filePath()))
                .build();

        final String json = objectMapper.writeValueAsString(enriched);
        assertThat(json).contains("_actions");
        assertThat(json).contains("fetchContent");
        assertThat(json).contains("getDocumentDetails");
    }

    @Test
    @DisplayName("SearchTools can be constructed and exposes simpleSearch tool spec")
    void searchToolsConstructionAndToolSpecExposure() {
        final LuceneIndexService mockService = mock(LuceneIndexService.class);
        final QueryRuntimeStats stats = new QueryRuntimeStats();
        final ApplicationConfig config = mock(ApplicationConfig.class);
        when(config.isToolActive(anyString())).thenReturn(true);

        final SearchTools tools = new SearchTools(mockService, stats, config);

        assertThat(tools.getToolSpecifications()).isNotEmpty();
        assertThat(tools.getToolSpecifications().stream()
                .anyMatch(s -> "simpleSearch".equals(s.tool().name()))).isTrue();
        assertThat(tools.getToolSpecifications().stream()
                .anyMatch(s -> "extendedSearch".equals(s.tool().name()))).isTrue();
        assertThat(tools.getToolSpecifications().stream()
                .anyMatch(s -> "semanticSearch".equals(s.tool().name()))).isTrue();
    }

    @Test
    @DisplayName("drillDown action for a facet is reflected in SearchResponse _actions")
    void drillDownActionAppearsInSearchResponseActions() {
        final SearchState state = new SearchState("report", List.of(), 0, 10);
        final Map<String, List<SearchResponse.FacetValue>> facets = Map.of(
                "language", List.of(
                        new SearchResponse.FacetValue("de", 15L),
                        new SearchResponse.FacetValue("en", 8L)
                )
        );
        final List<SearchAction> responseActions = generator.generateResponseActions(
                "simpleSearch", state, facets, false, false);

        assertThat(responseActions).anyMatch(a -> "drillDown".equals(a.type()));
        final SearchAction drillDown = responseActions.stream()
                .filter(a -> "drillDown".equals(a.type()))
                .findFirst()
                .orElseThrow();
        assertThat(drillDown.hits()).isNotNull();
        assertThat(drillDown.hits()).isEqualTo(15L);
    }

    @Test
    @DisplayName("prevPage and nextPage both present when in middle of result set")
    void prevAndNextPageBothPresentInMiddleOfResults() {
        final SearchState state = new SearchState("test", List.of(), 2, 10);
        final List<SearchAction> actions = generator.generateResponseActions(
                "extendedSearch", state, Map.of(), true, true);

        assertThat(actions).anyMatch(a -> "prevPage".equals(a.type()));
        assertThat(actions).anyMatch(a -> "nextPage".equals(a.type()));

        final SearchAction prev = actions.stream()
                .filter(a -> "prevPage".equals(a.type()))
                .findFirst()
                .orElseThrow();
        assertThat(prev.parameters().get("page")).isEqualTo(1);

        final SearchAction next = actions.stream()
                .filter(a -> "nextPage".equals(a.type()))
                .findFirst()
                .orElseThrow();
        assertThat(next.parameters().get("page")).isEqualTo(3);
    }
}
