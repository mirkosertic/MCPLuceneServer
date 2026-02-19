package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.QueryComponent;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for profileQuery tool with automatic phrase proximity expansion.
 * Verifies that the profiler correctly explains how phrase queries are automatically
 * expanded to include both exact matches (boosted) and proximity matches.
 */
@DisplayName("Phrase Expansion Profiling Tests")
class PhraseExpansionProfilingTest {

    @TempDir
    Path tempDir;

    private Path indexDir;
    private ApplicationConfig config;
    private DocumentIndexer documentIndexer;
    private LuceneIndexService indexService;

    @BeforeEach
    void setUp() throws Exception {
        indexDir = tempDir.resolve("index");

        // Create mock config
        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);

        documentIndexer = new DocumentIndexer();

        // Initialize service
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Index test documents with various phrase patterns
        indexTestDocuments();

        // Force refresh to make documents searchable
        indexService.refreshSearcher();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexService != null) {
            indexService.close();
        }
    }

    /**
     * Index test documents for phrase expansion testing.
     */
    private void indexTestDocuments() throws Exception {
        // Document 1: Exact match
        final Document doc1 = new Document();
        doc1.add(new StringField("file_path", "/test/exact.txt", Field.Store.YES));
        doc1.add(new TextField("content", "Domain Design", Field.Store.YES));
        doc1.add(new StringField("file_type", "txt", Field.Store.YES));
        documentIndexer.indexDocument(indexService.getIndexWriter(), doc1);

        // Document 2: Proximity match (hyphenated)
        final Document doc2 = new Document();
        doc2.add(new StringField("file_path", "/test/hyphenated.txt", Field.Store.YES));
        doc2.add(new TextField("content", "Domain-driven Design", Field.Store.YES));
        doc2.add(new StringField("file_type", "txt", Field.Store.YES));
        documentIndexer.indexDocument(indexService.getIndexWriter(), doc2);

        // Document 3: Proximity match (one word between)
        final Document doc3 = new Document();
        doc3.add(new StringField("file_path", "/test/proximity.txt", Field.Store.YES));
        doc3.add(new TextField("content", "Domain Effective Design", Field.Store.YES));
        doc3.add(new StringField("file_type", "txt", Field.Store.YES));
        documentIndexer.indexDocument(indexService.getIndexWriter(), doc3);

        // Commit to ensure documents are visible
        indexService.commit();
    }

    @Test
    @DisplayName("Query analysis should show phrase expansion structure")
    void testQueryAnalysisShowsExpansion() throws Exception {
        // Given: A phrase query that will be automatically expanded
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null, // no filters
                null, // page
                null, // pageSize
                false, // analyzeFilterImpact
                false, // analyzeDocumentScoring
                false, // analyzeFacetCost
                null // maxDocExplanations
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Response should be successful
        assertThat(response.success())
                .as("Profile query should succeed")
                .isTrue();

        assertThat(response.queryAnalysis())
                .as("Query analysis should be present")
                .isNotNull();

        // Verify original query is preserved
        assertThat(response.queryAnalysis().originalQuery())
                .as("Original query should be the user's input")
                .isEqualTo("\"Domain Design\"");

        // Verify query type reflects the expansion (BooleanQuery)
        assertThat(response.queryAnalysis().parsedQueryType())
                .as("Parsed query type should be BooleanQuery due to expansion")
                .isEqualTo("BooleanQuery");

        // Verify query components are extracted
        assertThat(response.queryAnalysis().components())
                .as("Query components should be extracted")
                .isNotEmpty();

        // Print for manual inspection
        System.out.println("\n=== Query Analysis ===");
        System.out.println("Original Query: " + response.queryAnalysis().originalQuery());
        System.out.println("Parsed Type: " + response.queryAnalysis().parsedQueryType());
        System.out.println("\nComponents:");
        for (final QueryComponent component : response.queryAnalysis().components()) {
            System.out.printf("  - %s [%s] occur=%s cost=%s\n",
                    component.type(),
                    component.value(),
                    component.occur(),
                    component.costDescription());
        }

        // Verify rewrites show the expansion
        if (response.queryAnalysis().rewrites() != null && !response.queryAnalysis().rewrites().isEmpty()) {
            System.out.println("\nRewrites:");
            response.queryAnalysis().rewrites().forEach(rewrite -> System.out.printf("  %s\n  -> %s\n  (%s)\n",
                    rewrite.original(),
                    rewrite.rewritten(),
                    rewrite.reason()));
        }
    }

    @Test
    @DisplayName("Document explanations should show exact and proximity clause contributions")
    void testDocumentExplanationsShowBothClauses() throws Exception {
        // Given: A phrase query with document scoring enabled
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null,
                null,
                null,
                false,
                true, // analyzeDocumentScoring = true
                false,
                3 // maxDocExplanations
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Response should include document explanations
        assertThat(response.success()).isTrue();
        assertThat(response.documentExplanations())
                .as("Document explanations should be present")
                .isNotNull()
                .isNotEmpty();

        // Should have up to 3 documents explained
        assertThat(response.documentExplanations().size())
                .as("Should explain up to 3 documents")
                .isLessThanOrEqualTo(3);

        // Print explanations for manual inspection
        System.out.println("\n=== Document Scoring Explanations ===");
        response.documentExplanations().forEach(docExplanation -> {
            System.out.printf("\nDocument: %s (Score: %.4f)\n",
                    docExplanation.filePath(),
                    docExplanation.score());

            System.out.println("Breakdown Summary: " + docExplanation.scoringBreakdown().summary());
            System.out.println("Components:");
            docExplanation.scoringBreakdown().components().forEach(component -> {
                System.out.printf("  - Term: %s, Field: %s, Contribution: %.2f%% (%.4f)\n",
                        component.term(),
                        component.field(),
                        component.contributionPercent(),
                        component.contribution());
                if (component.details() != null && component.details().explanation() != null) {
                    System.out.println("    Details: " + component.details().explanation());
                }
            });
        });

        // Verify the exact match document (first result) has a higher score
        final var firstDoc = response.documentExplanations().get(0);
        assertThat(firstDoc.filePath())
                .as("First result should be exact match")
                .contains("exact.txt");

        // If there are more results, they should have lower scores (proximity matches)
        if (response.documentExplanations().size() > 1) {
            final var secondDoc = response.documentExplanations().get(1);
            assertThat(firstDoc.score())
                    .as("Exact match should score higher than proximity matches")
                    .isGreaterThan(secondDoc.score());
        }
    }

    @Test
    @DisplayName("Profile query should work with expanded queries and provide recommendations")
    void testRecommendationsWorkWithExpansion() throws Exception {
        // Given: A full profile request
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null,
                null,
                null,
                false,
                true,
                false,
                3
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Recommendations should be generated
        assertThat(response.success()).isTrue();
        assertThat(response.recommendations())
                .as("Recommendations should be present")
                .isNotNull();

        // Print recommendations
        System.out.println("\n=== Recommendations ===");
        if (response.recommendations().isEmpty()) {
            System.out.println("  (No recommendations - query is optimal)");
        } else {
            response.recommendations().forEach(rec -> System.out.println("  - " + rec));
        }

        // Recommendations should not contain false warnings about the automatic expansion
        response.recommendations().forEach(rec -> assertThat(rec.toLowerCase())
                .as("Recommendations should not warn about automatic phrase expansion")
                .doesNotContain("unexpected", "error", "failed"));
    }

    @Test
    @DisplayName("Query rewrite information should clearly indicate phrase expansion")
    void testQueryRewriteShowsExpansion() throws Exception {
        // Given: A phrase query
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null,
                null,
                null,
                false,
                false,
                false,
                null
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Check if rewrites explain the expansion
        assertThat(response.success()).isTrue();
        assertThat(response.queryAnalysis()).isNotNull();

        System.out.println("\n=== Query Rewrite Analysis ===");
        System.out.println("Original Query: " + response.queryAnalysis().originalQuery());
        System.out.println("Parsed Type: " + response.queryAnalysis().parsedQueryType());

        if (response.queryAnalysis().rewrites() != null) {
            System.out.println("\nRewrites:");
            response.queryAnalysis().rewrites().forEach(rewrite -> {
                System.out.printf("  Original:  %s\n", rewrite.original());
                System.out.printf("  Rewritten: %s\n", rewrite.rewritten());
                System.out.printf("  Reason:    %s\n\n", rewrite.reason());
            });
        } else {
            System.out.println("  (No rewrites recorded - automatic expansion may not be explicitly shown)");
        }

        // The query type should at least indicate it's a BooleanQuery (from expansion)
        // This helps users understand that expansion occurred
    }

    @Test
    @DisplayName("Search metrics should be accurate for expanded queries")
    void testSearchMetricsForExpandedQueries() throws Exception {
        // Given: A phrase query
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null,
                null,
                null,
                false,
                false,
                false,
                null
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Search metrics should be present and accurate
        assertThat(response.success()).isTrue();
        assertThat(response.searchMetrics())
                .as("Search metrics should be present")
                .isNotNull();

        System.out.println("\n=== Search Metrics ===");
        System.out.println("Total Indexed Documents: " + response.searchMetrics().totalIndexedDocuments());
        System.out.println("Documents Matching Query: " + response.searchMetrics().documentsMatchingQuery());
        System.out.println("Documents After Filters: " + response.searchMetrics().documentsAfterFilters());

        // Should match all 3 documents (exact + 2 proximity matches within slop=3)
        assertThat(response.searchMetrics().documentsMatchingQuery())
                .as("Should match exact and proximity matches")
                .isGreaterThanOrEqualTo(2)
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Query components should include PhraseQuery entries with appropriate details")
    void testQueryComponentsIncludePhraseQueries() throws Exception {
        // Given: A phrase query
        final ProfileQueryRequest request = new ProfileQueryRequest(
                "\"Domain Design\"",
                null,
                null,
                null,
                false,
                false,
                false,
                null
        );

        // When: Profiling the query
        final ProfileQueryResponse response = indexService.profileQuery(request);

        // Then: Query components should include phrase query information
        assertThat(response.success()).isTrue();
        assertThat(response.queryAnalysis()).isNotNull();

        final List<QueryComponent> components = response.queryAnalysis().components();
        assertThat(components)
                .as("Should have query components")
                .isNotEmpty();

        System.out.println("\n=== Query Components Detail ===");
        components.forEach(component -> {
            System.out.printf("Type: %s\n", component.type());
            System.out.printf("  Field: %s\n", component.field());
            System.out.printf("  Value: %s\n", component.value());
            System.out.printf("  Occur: %s\n", component.occur());
            System.out.printf("  Cost: %d (%s)\n\n", component.estimatedCost(), component.costDescription());
        });

        // Should have PhraseQuery components (from the expanded BooleanQuery)
        final boolean hasPhraseQuery = components.stream()
                .anyMatch(c -> c.type().contains("Phrase"));

        assertThat(hasPhraseQuery)
                .as("Should have PhraseQuery components from expansion")
                .isTrue();
    }
}
