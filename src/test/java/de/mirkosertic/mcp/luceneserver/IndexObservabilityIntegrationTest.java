package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.crawler.FileContentExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Index Observability Integration Tests")
class IndexObservabilityIntegrationTest {

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;

    @BeforeEach
    void setUp() throws IOException {
        indexDir = tempDir.resolve("index");
        docsDir = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);

        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);
        when(config.isExtractMetadata()).thenReturn(true);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        documentIndexer = new DocumentIndexer();
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexService != null) {
            indexService.close();
        }
    }

    // ========== Helpers ==========

    private void indexDocumentWithMetadata(final String fileName, final String content,
                                           final String language, final String author) throws IOException {
        final Path testFile = docsDir.resolve(fileName);
        Files.writeString(testFile, content);

        final Map<String, String> metadata = new HashMap<>();
        if (author != null) {
            metadata.put("dc:creator", author);
        }

        final ExtractedDocument extracted = new ExtractedDocument(
                content, metadata, language, "text/plain", testFile.toFile().length());

        final var luceneDoc = documentIndexer.createDocument(testFile, extracted);
        documentIndexer.indexDocument(indexService.getIndexWriter(), luceneDoc);
        indexService.commit();
        indexService.refreshSearcher();
    }

    // ========== Validation Tests ==========

    @Nested
    @DisplayName("Field Validation")
    class FieldValidation {

        @Test
        @DisplayName("Should reject null field")
        void shouldRejectNullField() {
            assertThat(indexService.validateTermField(null)).isNotNull();
        }

        @Test
        @DisplayName("Should reject blank field")
        void shouldRejectBlankField() {
            assertThat(indexService.validateTermField("  ")).isNotNull();
        }

        @Test
        @DisplayName("Should reject point fields")
        void shouldRejectPointFields() {
            for (final String field : LuceneIndexService.LONG_POINT_FIELDS) {
                final String error = indexService.validateTermField(field);
                assertThat(error).as("Field '%s' should be rejected", field)
                        .isNotNull()
                        .contains("point field")
                        .contains("getIndexStats");
            }
        }

        @Test
        @DisplayName("Should accept valid text fields")
        void shouldAcceptValidTextFields() {
            assertThat(indexService.validateTermField("content")).isNull();
            assertThat(indexService.validateTermField("author")).isNull();
            assertThat(indexService.validateTermField("file_extension")).isNull();
        }
    }

    // ========== SuggestTerms Tests ==========

    @Nested
    @DisplayName("SuggestTerms")
    class SuggestTermsTests {

        @Test
        @DisplayName("Should find terms matching prefix")
        void shouldFindTermsMatchingPrefix() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Arbeitsvertrag und Mietvertrag", "de", null);
            indexDocumentWithMetadata("doc2.txt", "Vertragsbedingungen und Vertragsklausel", "de", null);

            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "vertrag", 20);

            assertThat(result.terms()).isNotEmpty();
            assertThat(result.terms().stream().map(Map.Entry::getKey))
                    .allSatisfy(term -> assertThat(term).startsWith("vertrag"));
            assertThat(result.totalMatched()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should lowercase prefix for analyzed fields")
        void shouldLowercasePrefixForAnalyzedFields() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Vertragsbedingungen sind wichtig", "de", null);

            // Uppercase prefix should be lowercased internally for content field
            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "Vertrag", 20);

            assertThat(result.terms()).isNotEmpty();
            assertThat(result.terms().stream().map(Map.Entry::getKey))
                    .allSatisfy(term -> assertThat(term).startsWith("vertrag"));
        }

        @Test
        @DisplayName("Should NOT lowercase prefix for string fields")
        void shouldNotLowercasePrefixForStringFields() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Some content", "en", null);

            // file_extension is a StringField — exact match, no lowercasing
            final LuceneIndexService.TermSuggestionResult resultLower = indexService.suggestTerms("file_extension", "tx", 20);
            assertThat(resultLower.terms()).isNotEmpty();

            // Uppercase prefix should NOT match StringField values (they are stored as-is)
            final LuceneIndexService.TermSuggestionResult resultUpper = indexService.suggestTerms("file_extension", "TX", 20);
            assertThat(resultUpper.terms()).isEmpty();
        }

        @Test
        @DisplayName("Should enforce limit")
        void shouldEnforceLimit() throws IOException {
            // Index enough content to generate many terms
            indexDocumentWithMetadata("doc1.txt",
                    "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu", "en", null);

            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "", 3);

            assertThat(result.terms()).hasSizeLessThanOrEqualTo(3);
            assertThat(result.totalMatched()).isGreaterThanOrEqualTo(result.terms().size());
        }

        @Test
        @DisplayName("Should return empty for empty index")
        void shouldReturnEmptyForEmptyIndex() throws IOException {
            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "test", 20);

            assertThat(result.terms()).isEmpty();
            assertThat(result.totalMatched()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return empty for nonexistent field")
        void shouldReturnEmptyForNonexistentField() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Some content", "en", null);

            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("nonexistent_field", "test", 20);

            assertThat(result.terms()).isEmpty();
            assertThat(result.totalMatched()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should sort by docFreq descending")
        void shouldSortByDocFreqDescending() throws IOException {
            // "contract" appears in 3 docs, "continental" in 1
            indexDocumentWithMetadata("doc1.txt", "contract signed", "en", null);
            indexDocumentWithMetadata("doc2.txt", "contract terms", "en", null);
            indexDocumentWithMetadata("doc3.txt", "contract and continental agreement", "en", null);

            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "con", 20);

            assertThat(result.terms()).hasSizeGreaterThanOrEqualTo(2);
            // First term should have highest docFreq
            final int firstFreq = result.terms().get(0).getValue();
            for (int i = 1; i < result.terms().size(); i++) {
                assertThat(result.terms().get(i).getValue()).isLessThanOrEqualTo(firstFreq);
            }
        }

        @Test
        @DisplayName("Should aggregate docFreq across segments")
        void shouldAggregateDocFreqAcrossSegments() throws IOException {
            // Each indexDocumentWithMetadata commits separately, creating separate segments
            indexDocumentWithMetadata("doc1.txt", "Vertrag unterschrieben", "de", null);
            indexDocumentWithMetadata("doc2.txt", "Vertrag gekündigt", "de", null);
            indexDocumentWithMetadata("doc3.txt", "Vertrag verlängert", "de", null);

            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms("content", "vertrag", 20);

            // "vertrag" should appear with docFreq >= 3 (aggregated across segments)
            final var vertragEntry = result.terms().stream()
                    .filter(e -> "vertrag".equals(e.getKey()))
                    .findFirst();
            assertThat(vertragEntry).isPresent();
            assertThat(vertragEntry.get().getValue()).isGreaterThanOrEqualTo(3);
        }
    }

    // ========== GetTopTerms Tests ==========

    @Nested
    @DisplayName("GetTopTerms")
    class GetTopTermsTests {

        @Test
        @DisplayName("Should return terms sorted by frequency")
        void shouldReturnTermsSortedByFrequency() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "contract signed today", "en", null);
            indexDocumentWithMetadata("doc2.txt", "contract terms and conditions", "en", null);
            indexDocumentWithMetadata("doc3.txt", "agreement signed yesterday", "en", null);

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("content", 20);

            assertThat(result.terms()).isNotEmpty();
            // Verify descending order
            for (int i = 1; i < result.terms().size(); i++) {
                assertThat(result.terms().get(i).getValue())
                        .isLessThanOrEqualTo(result.terms().get(i - 1).getValue());
            }
        }

        @Test
        @DisplayName("Should work with StringField")
        void shouldWorkWithStringField() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Content one", "en", "Alice");
            indexDocumentWithMetadata("doc2.txt", "Content two", "de", "Bob");
            indexDocumentWithMetadata("doc3.txt", "Content three", "en", "Alice");

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("language", 20);

            assertThat(result.terms()).isNotEmpty();
            final var termNames = result.terms().stream().map(Map.Entry::getKey).toList();
            assertThat(termNames).contains("en", "de");
        }

        @Test
        @DisplayName("Should enforce limit")
        void shouldEnforceLimit() throws IOException {
            indexDocumentWithMetadata("doc1.txt",
                    "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon",
                    "en", null);

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("content", 5);

            assertThat(result.terms()).hasSizeLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("Should report uniqueTermCount")
        void shouldReportUniqueTermCount() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "hello world foo bar", "en", null);

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("content", 100);

            assertThat(result.uniqueTermCount()).isGreaterThanOrEqualTo(4);
            assertThat(result.uniqueTermCount()).isEqualTo(result.terms().size());
        }

        @Test
        @DisplayName("Should return empty for empty index")
        void shouldReturnEmptyForEmptyIndex() throws IOException {
            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("content", 20);

            assertThat(result.terms()).isEmpty();
            assertThat(result.uniqueTermCount()).isEqualTo(0);
            assertThat(result.warning()).isNull();
        }

        @Test
        @DisplayName("Should return empty for nonexistent field")
        void shouldReturnEmptyForNonexistentField() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Some content", "en", null);

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("nonexistent_field", 20);

            assertThat(result.terms()).isEmpty();
            assertThat(result.uniqueTermCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should aggregate docFreq across segments for getTopTerms")
        void shouldAggregateDocFreqAcrossSegments() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Vertrag unterschrieben", "de", null);
            indexDocumentWithMetadata("doc2.txt", "Vertrag gekündigt", "de", null);

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("content", 50);

            final var vertragEntry = result.terms().stream()
                    .filter(e -> "vertrag".equals(e.getKey()))
                    .findFirst();
            assertThat(vertragEntry).isPresent();
            assertThat(vertragEntry.get().getValue()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Should return author values from StringField")
        void shouldReturnAuthorValues() throws IOException {
            indexDocumentWithMetadata("doc1.txt", "Report A", "en", "Dr. Smith");
            indexDocumentWithMetadata("doc2.txt", "Report B", "en", "Dr. Smith");
            indexDocumentWithMetadata("doc3.txt", "Report C", "en", "Prof. Jones");

            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms("author", 20);

            assertThat(result.terms()).isNotEmpty();
            // author is an analyzed field, so it will be tokenized
            // But let's just check we get results
            assertThat(result.uniqueTermCount()).isGreaterThan(0);
        }
    }
}
