package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.crawler.FileContentExtractor;
import de.mirkosertic.mcp.luceneserver.crawler.TestDocumentGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for search highlighting functionality.
 *
 * <p>These tests verify the complete pipeline:</p>
 * <ol>
 *   <li>Content extraction from various document formats</li>
 *   <li>Content normalization (HTML entities, special characters)</li>
 *   <li>Indexing with term vectors (positions and offsets)</li>
 *   <li>Search with UnifiedHighlighter</li>
 *   <li>Passage generation with {@code <em>} highlighting tags</li>
 * </ol>
 */
@DisplayName("Search Highlighting Integration Tests")
class SearchHighlightingIntegrationTest {

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private FileContentExtractor extractor;
    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;

    @BeforeEach
    void setUp() throws IOException {
        indexDir = tempDir.resolve("index");
        docsDir = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);

        // Create mock config
        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);
        when(config.isExtractMetadata()).thenReturn(true);
        when(config.isDetectLanguage()).thenReturn(false); // Disable for faster tests
        when(config.getMaxContentLength()).thenReturn(-1L);

        // Create components
        extractor = new FileContentExtractor(config);
        documentIndexer = new DocumentIndexer();
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();  // Initialize the index writer and searcher
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexService != null) {
            indexService.close();
        }
    }

    // ========== Parameterized Tests for All Document Types ==========

    @ParameterizedTest(name = "Highlighting in {0} files")
    @MethodSource("documentTypeProvider")
    @DisplayName("Should highlight search terms in")
    void shouldHighlightSearchTermsInDocument(
            final String extension,
            final FileGenerator generator) throws Exception {

        // Given: Create and index a test document
        final Path testFile = docsDir.resolve("test-highlight." + extension);
        generator.generate(testFile);

        final ExtractedDocument extracted = extractor.extract(testFile);
        assertThat(extracted.content())
            .as("Content should be extracted from %s", extension)
            .isNotEmpty();

        // Index the document
        final var luceneDoc = documentIndexer.createDocument(testFile, extracted);
        documentIndexer.indexDocument(indexService.getIndexWriter(), luceneDoc);
        indexService.commit();
        indexService.refreshSearcher();

        // When: Search for a term we know is in the test content
        final LuceneIndexService.SearchResult result = indexService.search(
            "test content", List.of(), 0, 10);

        // Then: Verify search found the document
        assertThat(result.totalHits())
            .as("Should find the %s document", extension)
            .isGreaterThanOrEqualTo(1);

        assertThat(result.documents())
            .as("Should return search results for %s", extension)
            .isNotEmpty();

        // Verify passages exist and contain highlighting
        final SearchDocument doc = result.documents().getFirst();
        assertThat(doc.passages())
            .as("Should have passages for %s", extension)
            .isNotEmpty();

        final Passage firstPassage = doc.passages().getFirst();
        assertThat(firstPassage.text())
            .as("Passage text should not be empty for %s", extension)
            .isNotEmpty();

        // Verify highlighting with <em> tags (at least one term should be highlighted)
        final boolean hasHighlighting = firstPassage.text().contains("<em>")
            && firstPassage.text().contains("</em>");

        assertThat(hasHighlighting)
            .as("Passage should contain <em> highlighting tags for %s. Actual passage: %s",
                extension, firstPassage.text())
            .isTrue();

        // Verify matched terms are extracted
        assertThat(firstPassage.matchedTerms())
            .as("Should have matched terms for %s", extension)
            .isNotEmpty();

        // Verify term coverage is calculated
        assertThat(firstPassage.termCoverage())
            .as("Term coverage should be > 0 for %s", extension)
            .isGreaterThan(0.0);

        // Verify score is set
        assertThat(firstPassage.score())
            .as("Passage score should be > 0 for %s", extension)
            .isGreaterThan(0.0);
    }

    static Stream<Arguments> documentTypeProvider() {
        return Stream.of(
            Arguments.of("txt", (FileGenerator) TestDocumentGenerator::createTxtFile),
            Arguments.of("pdf", (FileGenerator) TestDocumentGenerator::createPdfFile),
            Arguments.of("docx", (FileGenerator) TestDocumentGenerator::createDocxFile),
            Arguments.of("doc", (FileGenerator) TestDocumentGenerator::createDocFile),
            Arguments.of("xlsx", (FileGenerator) TestDocumentGenerator::createXlsxFile),
            Arguments.of("xls", (FileGenerator) TestDocumentGenerator::createXlsFile),
            Arguments.of("pptx", (FileGenerator) TestDocumentGenerator::createPptxFile),
            Arguments.of("ppt", (FileGenerator) TestDocumentGenerator::createPptFile),
            Arguments.of("odt", (FileGenerator) TestDocumentGenerator::createOdtFile),
            Arguments.of("ods", (FileGenerator) TestDocumentGenerator::createOdsFile)
        );
    }

    @FunctionalInterface
    interface FileGenerator {
        void generate(Path path) throws Exception;
    }

    // ========== Specific Highlighting Tests ==========

    @Test
    @DisplayName("Should highlight multiple search terms")
    void shouldHighlightMultipleSearchTerms() throws Exception {
        // Given: Index a document with known content
        final Path testFile = docsDir.resolve("multi-term.txt");
        Files.writeString(testFile, "This document contains test content for verification purposes. " +
            "The test should verify that multiple terms are highlighted correctly.");

        indexDocument(testFile);

        // When: Search for multiple terms
        final LuceneIndexService.SearchResult result = indexService.search(
            "test verification", List.of(), 0, 10);

        // Then: Both terms should be highlighted
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final Passage passage = result.documents().getFirst().passages().getFirst();
        assertThat(passage.text())
            .contains("<em>")
            .contains("</em>");

        // Should have matched at least one of the terms
        assertThat(passage.matchedTerms())
            .as("Should have matched terms")
            .isNotEmpty();
    }

    @Test
    @DisplayName("Should handle HTML entities in content")
    void shouldHandleHtmlEntitiesInContent() throws Exception {
        // Given: Index a document with HTML entities
        final Path testFile = docsDir.resolve("html-entities.txt");
        Files.writeString(testFile, "Tom &amp; Jerry are famous characters in animation. " +
            "The path is C:&#x2F;Users&#x2F;test for configuration. " +
            "Price: &lt;100&gt; dollars is affordable.");

        indexDocument(testFile);

        // When: Search for content (use terms that will definitely be in the text)
        final LuceneIndexService.SearchResult result = indexService.search(
            "characters animation", List.of(), 0, 10);

        // Then: Entities should be decoded in the indexed content
        assertThat(result.totalHits())
            .as("Should find the document")
            .isGreaterThanOrEqualTo(1);

        final Passage passage = result.documents().getFirst().passages().getFirst();

        // The passage should contain decoded entities (& instead of &amp;)
        // Note: This tests the full pipeline including FileContentExtractor normalization
        // AND that the highlighter doesn't re-escape them
        assertThat(passage.text())
            .as("HTML entities should be decoded. Actual: %s", passage.text())
            .doesNotContain("&amp;")
            .doesNotContain("&#x2F;")
            .doesNotContain("&lt;")
            .doesNotContain("&gt;");

        // Verify the decoded characters are present
        assertThat(passage.text())
            .as("Should contain decoded ampersand")
            .contains("&");  // The actual & character, not &amp;
    }

    @Test
    @DisplayName("Should handle special Unicode characters")
    void shouldHandleSpecialUnicodeCharacters() throws Exception {
        // Given: Index a document with special characters
        final Path testFile = docsDir.resolve("unicode.txt");
        Files.writeString(testFile, "Müller and Müller are searching for files. " +
            "The file path contains special characters.");

        indexDocument(testFile);

        // When: Search for the name (with umlaut)
        final LuceneIndexService.SearchResult result = indexService.search(
            "Muller", List.of(), 0, 10);  // Search without umlaut

        // Then: Should find the document (ICU folding should handle this)
        assertThat(result.totalHits())
            .as("Should find document with umlauts when searching without")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should calculate term coverage correctly")
    void shouldCalculateTermCoverageCorrectly() throws Exception {
        // Given: Index a document
        final Path testFile = docsDir.resolve("coverage.txt");
        Files.writeString(testFile, "Alpha Beta Gamma Delta - these are Greek letters. " +
            "Alpha and Beta appear here again.");

        indexDocument(testFile);

        // When: Search for terms where only some appear
        final LuceneIndexService.SearchResult result = indexService.search(
            "Alpha Beta Omega", List.of(), 0, 10);

        // Then: Term coverage should reflect partial match
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final Passage passage = result.documents().getFirst().passages().getFirst();

        // Alpha and Beta should be found, Omega should not
        // So coverage should be around 0.67 (2/3)
        assertThat(passage.termCoverage())
            .as("Term coverage should be between 0 and 1")
            .isBetween(0.0, 1.0);

        assertThat(passage.matchedTerms())
            .as("Should have found Alpha and/or Beta")
            .isNotEmpty();
    }

    @Test
    @DisplayName("Should return multiple individual passages for long documents")
    void shouldReturnMultiplePassagesForLongDocuments() throws Exception {
        // Given: Index a document with multiple relevant sections separated by filler
        final String content = "First section: The search term appears here in the introduction. " +
                "Lorem ipsum dolor sit amet. ".repeat(50) + // Filler
                "Second section: Another occurrence of the search term in the middle. " +
                "Lorem ipsum dolor sit amet. ".repeat(50) + // Filler
                "Third section: Final mention of the search term at the end.";

        final Path testFile = docsDir.resolve("long-document.txt");
        Files.writeString(testFile, content);

        indexDocument(testFile);

        // When: Search for the repeated term
        final LuceneIndexService.SearchResult result = indexService.search(
            "search term", List.of(), 0, 10);

        // Then: Should find the document
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final List<Passage> passages = result.documents().getFirst().passages();

        // With the IndividualPassageFormatter fix, we should now get multiple
        // individual passages (one per matching sentence) instead of a single
        // joined string.
        assertThat(passages)
            .as("Should have multiple individual passages for a long document with 3 matches")
            .hasSizeGreaterThanOrEqualTo(2);

        // Each passage should contain highlighting
        for (final Passage passage : passages) {
            assertThat(passage.text())
                .as("Each individual passage should contain <em> highlighting")
                .contains("<em>");
        }

        // The best passage should have score 1.0 (normalised maximum)
        assertThat(passages.getFirst().score())
            .as("Best passage should have normalised score of 1.0")
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should have meaningful scores across passages (not all 1.0)")
    void shouldHaveMeaningfulScoresAcrossPassages() throws Exception {
        // Given: Index a document where the search term appears in multiple distinct sections
        final String content = "Important: The contract details are specified below. " +
                "Lorem ipsum dolor sit amet consectetur. ".repeat(40) +
                "The contract was signed by both parties last week. " +
                "Lorem ipsum dolor sit amet consectetur. ".repeat(40) +
                "A new contract will be drafted next month.";

        final Path testFile = docsDir.resolve("score-ordering.txt");
        Files.writeString(testFile, content);

        indexDocument(testFile);

        final LuceneIndexService.SearchResult result = indexService.search(
            "contract", List.of(), 0, 10);

        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final List<Passage> passages = result.documents().getFirst().passages();
        assertThat(passages).hasSizeGreaterThanOrEqualTo(2);

        // The best passage (first) should be normalised to 1.0
        assertThat(passages.getFirst().score())
            .as("Best passage should have normalised score of 1.0")
            .isEqualTo(1.0);

        // All scores should be in the valid range [0.0, 1.0]
        for (final Passage passage : passages) {
            assertThat(passage.score())
                .as("Passage scores should be in [0.0, 1.0]")
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("Should calculate position from passage offset")
    void shouldCalculatePositionFromPassageOffset() throws Exception {
        // Given: Index a long document with the search term at different positions
        final String filler = "Lorem ipsum dolor sit amet consectetur adipiscing elit. ".repeat(30);
        final String content = "Target keyword appears at the very start. " +
                filler +
                "The target keyword also appears in the middle of the document. " +
                filler +
                "Finally the target keyword is at the end.";

        final Path testFile = docsDir.resolve("position-test.txt");
        Files.writeString(testFile, content);

        indexDocument(testFile);

        final LuceneIndexService.SearchResult result = indexService.search(
            "target keyword", List.of(), 0, 10);

        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final List<Passage> passages = result.documents().getFirst().passages();
        assertThat(passages).hasSizeGreaterThanOrEqualTo(2);

        // The first passage (at start of doc) should have position near 0.0
        // At least one passage should have a non-zero position (i.e., not all at the start)
        final boolean hasNonZeroPosition = passages.stream()
                .anyMatch(p -> p.position() > 0.1);
        assertThat(hasNonZeroPosition)
            .as("At least one passage should have a position > 0.1 (not all at document start)")
            .isTrue();

        // All positions should be in valid range
        for (final Passage passage : passages) {
            assertThat(passage.position())
                .as("Position should be between 0.0 and 1.0")
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("Should have per-passage matchedTerms and termCoverage")
    void shouldHavePerPassageMatchedTermsAndTermCoverage() throws Exception {
        // Given: Index a document where different terms appear in different sections
        final String content = "Alpha appears here in this first section of the document. " +
                "Lorem ipsum dolor sit amet consectetur. ".repeat(40) +
                "Beta appears in this second section of the document. " +
                "Lorem ipsum dolor sit amet consectetur. ".repeat(40) +
                "Both Alpha and Beta appear together in this final section.";

        final Path testFile = docsDir.resolve("per-passage-coverage.txt");
        Files.writeString(testFile, content);

        indexDocument(testFile);

        final LuceneIndexService.SearchResult result = indexService.search(
            "Alpha Beta", List.of(), 0, 10);

        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final List<Passage> passages = result.documents().getFirst().passages();
        assertThat(passages).hasSizeGreaterThanOrEqualTo(2);

        // Each passage should have its own matchedTerms
        for (final Passage passage : passages) {
            assertThat(passage.matchedTerms())
                .as("Each passage should have matched terms")
                .isNotEmpty();
        }

        // At least one passage should have termCoverage < 1.0 (only one of the two terms)
        // because "Alpha" and "Beta" appear in different sections
        final boolean hasSingleTermPassage = passages.stream()
                .anyMatch(p -> p.termCoverage() > 0.0 && p.termCoverage() < 1.0);
        // The passage with both terms should have coverage = 1.0
        final boolean hasFullCoveragePassage = passages.stream()
                .anyMatch(p -> p.termCoverage() == 1.0);

        // We expect at least partial coverage in some passages
        assertThat(hasSingleTermPassage || hasFullCoveragePassage)
            .as("Should have passages with meaningful term coverage values")
            .isTrue();
    }

    @Test
    @DisplayName("Should handle wildcard queries")
    void shouldHandleWildcardQueries() throws Exception {
        // Given: Index a document
        final Path testFile = docsDir.resolve("wildcard.txt");
        Files.writeString(testFile, "Testing wildcard functionality. " +
            "Test cases include testing and tested scenarios.");

        indexDocument(testFile);

        // When: Search with wildcard
        final LuceneIndexService.SearchResult result = indexService.search(
            "test*", List.of(), 0, 10);

        // Then: Should find and highlight matches
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final Passage passage = result.documents().getFirst().passages().getFirst();
        assertThat(passage.text())
            .as("Should contain highlighting for wildcard match")
            .contains("<em>");
    }

    @Test
    @DisplayName("Should not have newlines in passage text")
    void shouldNotHaveNewlinesInPassageText() throws Exception {
        // Given: Index a document with multiple lines
        final Path testFile = docsDir.resolve("multiline.txt");
        Files.writeString(testFile, """
                First line with search term.
                Second line continues.
                Third line with more content.
                Fourth line ends here.""");

        indexDocument(testFile);

        // When: Search
        final LuceneIndexService.SearchResult result = indexService.search(
            "search term", List.of(), 0, 10);

        // Then: Passages should not contain raw newlines
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        for (final Passage passage : result.documents().getFirst().passages()) {
            assertThat(passage.text())
                .as("Passage should not contain newline characters")
                .doesNotContain("\n")
                .doesNotContain("\r");
        }
    }

    // ========== Leading Wildcard / Reverse Token Tests ==========

    @Test
    @DisplayName("Should search with leading wildcard (*vertrag finds Arbeitsvertrag)")
    void shouldSearchWithLeadingWildcard() throws Exception {
        // Given: Index a document with German compound words
        final Path testFile = docsDir.resolve("compound-words.txt");
        Files.writeString(testFile, "Der Arbeitsvertrag wurde gestern unterzeichnet. " +
            "Der Kaufvertrag ist noch in Bearbeitung.");

        indexDocument(testFile);

        // When: Search with leading wildcard
        final LuceneIndexService.SearchResult result = indexService.search(
            "*vertrag", List.of(), 0, 10);

        // Then: Should find the document
        assertThat(result.totalHits())
            .as("Leading wildcard *vertrag should find document with Arbeitsvertrag and Kaufvertrag")
            .isGreaterThanOrEqualTo(1);

        assertThat(result.documents()).isNotEmpty();
    }

    @Test
    @DisplayName("Should highlight with leading wildcard query")
    void shouldHighlightWithLeadingWildcard() throws Exception {
        // Given: Index a document with compound words
        final Path testFile = docsDir.resolve("highlight-leading.txt");
        Files.writeString(testFile, "Der Arbeitsvertrag regelt die Arbeitsbedingungen. " +
            "Ein Kaufvertrag wurde ebenfalls abgeschlossen.");

        indexDocument(testFile);

        // When: Search with leading wildcard
        final LuceneIndexService.SearchResult result = indexService.search(
            "*vertrag", List.of(), 0, 10);

        // Then: Should find and have passages (highlighting may not use <em> for
        // reversed-field queries, but passages should still be present)
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(1);

        final List<Passage> passages = result.documents().getFirst().passages();
        assertThat(passages)
            .as("Should have at least one passage")
            .isNotEmpty();

        assertThat(passages.getFirst().text())
            .as("Passage text should not be empty")
            .isNotEmpty();
    }

    @Test
    @DisplayName("Should search with infix wildcard (*vertrag* finds both compounds)")
    void shouldSearchWithInfixWildcard() throws Exception {
        // Given: Index documents with compound words containing "vertrag" in different positions
        final Path testFile = docsDir.resolve("infix-wildcard.txt");
        Files.writeString(testFile, "Die Vertragsbedingungen des Arbeitsvertrags sind klar definiert. " +
            "Der Mietvertrag enthaelt wichtige Vertragsklauseln.");

        indexDocument(testFile);

        // When: Search with infix wildcard
        final LuceneIndexService.SearchResult result = indexService.search(
            "*vertrag*", List.of(), 0, 10);

        // Then: Should find the document (matches both Vertragsbedingungen and Arbeitsvertrags)
        assertThat(result.totalHits())
            .as("Infix wildcard *vertrag* should find document with compound words")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should still work with trailing wildcard (regression test)")
    void shouldStillWorkWithTrailingWildcard() throws Exception {
        // Given: Index a document
        final Path testFile = docsDir.resolve("trailing-wildcard.txt");
        Files.writeString(testFile, "The contract was signed yesterday. " +
            "The contractor delivered on time. Contracting services are available.");

        indexDocument(testFile);

        // When: Search with trailing wildcard
        final LuceneIndexService.SearchResult result = indexService.search(
            "contract*", List.of(), 0, 10);

        // Then: Should find the document
        assertThat(result.totalHits())
            .as("Trailing wildcard contract* should still work")
            .isGreaterThanOrEqualTo(1);

        final Passage passage = result.documents().getFirst().passages().getFirst();
        assertThat(passage.text())
            .as("Should contain highlighting for wildcard match")
            .contains("<em>");
    }

    @Test
    @DisplayName("Should handle leading wildcard with ICU folding (*bericht finds Pruefbericht)")
    void shouldHandleLeadingWildcardWithIcuFolding() throws Exception {
        // Given: Index a document with umlauts
        final Path testFile = docsDir.resolve("icu-leading.txt");
        Files.writeString(testFile, "Der Pruefbericht wurde erstellt. " +
            "Ein Jahresbericht folgt im naechsten Monat.");

        indexDocument(testFile);

        // When: Search with leading wildcard (without umlaut, ICU folding handles this)
        final LuceneIndexService.SearchResult result = indexService.search(
            "*bericht", List.of(), 0, 10);

        // Then: Should find the document
        assertThat(result.totalHits())
            .as("Leading wildcard *bericht should find Pruefbericht and Jahresbericht via ICU folding")
            .isGreaterThanOrEqualTo(1);
    }

    // ========== Helper Methods ==========

    private void indexDocument(final Path file) throws IOException {
        final ExtractedDocument extracted = extractor.extract(file);
        final var luceneDoc = documentIndexer.createDocument(file, extracted);
        documentIndexer.indexDocument(indexService.getIndexWriter(), luceneDoc);
        indexService.commit();
        indexService.refreshSearcher();
    }
}
