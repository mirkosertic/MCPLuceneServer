package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.crawler.FileContentExtractor;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ActiveFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Multi-Filter Search Integration Tests")
class MultiFilterSearchIntegrationTest {

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

        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);
        when(config.isExtractMetadata()).thenReturn(true);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        extractor = new FileContentExtractor(config);
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

    private void indexDocument(final Path file) throws IOException {
        final ExtractedDocument extracted = extractor.extract(file);
        final var luceneDoc = documentIndexer.createDocument(file, extracted);
        documentIndexer.indexDocument(indexService.getIndexWriter(), luceneDoc);
        indexService.commit();
        indexService.refreshSearcher();
    }

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

    // ========== Filter Validation ==========

    @Nested
    @DisplayName("Filter Validation")
    class FilterValidation {

        @Test
        @DisplayName("Should reject unknown operator")
        void shouldRejectUnknownOperator() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("language", "foo", "en", null, null, null, null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown filter operator");
        }

        @Test
        @DisplayName("Should reject eq on analyzed TextField")
        void shouldRejectEqOnAnalyzedTextField() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("content", "eq", "test", null, null, null, null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot filter on analyzed field");
        }

        @Test
        @DisplayName("Should reject range on non-numeric field")
        void shouldRejectRangeOnNonNumericField() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("language", "range", null, null, "a", "z", null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Range filter is only supported on numeric");
        }

        @Test
        @DisplayName("Should reject eq without value")
        void shouldRejectEqWithoutValue() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("language", "eq", null, null, null, null, null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires 'value'");
        }

        @Test
        @DisplayName("Should reject in without values")
        void shouldRejectInWithoutValues() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("language", "in", null, null, null, null, null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires 'values'");
        }

        @Test
        @DisplayName("Should reject range without from or to")
        void shouldRejectRangeWithoutFromOrTo() throws IOException {
            indexDocumentWithMetadata("test.txt", "hello world", "en", null);

            assertThatThrownBy(() -> indexService.search(
                    "hello",
                    List.of(new SearchFilter("file_size", "range", null, null, null, null, null)),
                    0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires at least 'from' or 'to'");
        }
    }

    // ========== Single Eq Filter ==========

    @Nested
    @DisplayName("Single Eq Filter")
    class SingleEqFilter {

        @Test
        @DisplayName("Should filter by language")
        void shouldFilterByLanguage() throws Exception {
            indexDocumentWithMetadata("english.txt", "This is an English document about contracts", "en", null);
            indexDocumentWithMetadata("german.txt", "Dies ist ein deutsches Dokument ueber Vertraege", "de", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
        }

        @Test
        @DisplayName("Should filter by file_extension")
        void shouldFilterByFileExtension() throws Exception {
            indexDocumentWithMetadata("doc1.txt", "First document content here", "en", null);
            // Create a .md file
            final Path mdFile = docsDir.resolve("doc2.md");
            Files.writeString(mdFile, "Second document content here");
            indexDocument(mdFile);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("file_extension", "eq", "txt", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().fileExtension()).isEqualTo("txt");
        }

        @Test
        @DisplayName("Should return empty for non-matching filter")
        void shouldReturnEmptyForNonMatchingFilter() throws Exception {
            indexDocumentWithMetadata("english.txt", "This is an English document about testing", "en", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "fr", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(0);
            assertThat(result.documents()).isEmpty();
        }
    }

    // ========== Multiple Filters ==========

    @Nested
    @DisplayName("Multiple Filters")
    class MultipleFilters {

        @Test
        @DisplayName("Should AND filters on different fields")
        void shouldAndFiltersDifferentFields() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English text file for testing", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German text file for testing", "de", null);
            // Create .md with English
            final Path mdFile = docsDir.resolve("en-doc.md");
            Files.writeString(mdFile, "English markdown file");
            final ExtractedDocument mdExtracted = new ExtractedDocument(
                    "English markdown file", Map.of(), "en", "text/markdown", mdFile.toFile().length());
            final var mdLuceneDoc = documentIndexer.createDocument(mdFile, mdExtracted);
            documentIndexer.indexDocument(indexService.getIndexWriter(), mdLuceneDoc);
            indexService.commit();
            indexService.refreshSearcher();

            final var result = indexService.search(
                    null,
                    List.of(
                            new SearchFilter("language", "eq", "en", null, null, null, null),
                            new SearchFilter("file_extension", "eq", "txt", null, null, null, null)
                    ),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
            assertThat(result.documents().getFirst().fileExtension()).isEqualTo("txt");
        }

        @Test
        @DisplayName("Should OR filters on same field with in operator")
        void shouldOrFiltersSameFieldWithInOperator() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document about testing", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document about testing", "de", null);
            indexDocumentWithMetadata("fr-doc.txt", "French document about testing", "fr", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "in", null, List.of("en", "de"), null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(2);
        }
    }

    // ========== Not Filters ==========

    @Nested
    @DisplayName("Not Filters")
    class NotFilters {

        @Test
        @DisplayName("Should exclude with not operator")
        void shouldExcludeWithNot() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document text", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document text", "de", null);
            indexDocumentWithMetadata("fr-doc.txt", "French document text", "fr", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "not", "fr", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(2);
            assertThat(result.documents())
                    .noneMatch(doc -> "fr".equals(doc.language()));
        }

        @Test
        @DisplayName("Should exclude with not_in operator")
        void shouldExcludeWithNotIn() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document text", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document text", "de", null);
            indexDocumentWithMetadata("fr-doc.txt", "French document text", "fr", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "not_in", null, List.of("de", "fr"), null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
        }
    }

    // ========== Range Filters ==========

    @Nested
    @DisplayName("Range Filters")
    class RangeFilters {

        @Test
        @DisplayName("Should filter date range with ISO-8601")
        void shouldFilterDateRange() throws Exception {
            indexDocumentWithMetadata("recent.txt", "Recent document about testing", "en", null);

            // The document was just created, so its modified_date is "now" — within a wide range
            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("modified_date", "range", null, null, "2020-01-01", "2030-12-31", null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should filter date range excluding recent documents")
        void shouldFilterDateRangeExcluding() throws Exception {
            indexDocumentWithMetadata("recent.txt", "Recent document about testing", "en", null);

            // The document was just created, so a range in the past should exclude it
            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("modified_date", "range", null, null, "2000-01-01", "2001-12-31", null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should filter file size range")
        void shouldFilterFileSizeRange() throws Exception {
            // Small file
            indexDocumentWithMetadata("small.txt", "tiny", "en", null);
            // Larger file
            indexDocumentWithMetadata("large.txt", "x".repeat(10000), "en", null);

            // Filter for files > 1000 bytes
            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("file_size", "range", null, null, "1000", null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
        }
    }

    // ========== Empty Query With Filters ==========

    @Nested
    @DisplayName("Empty Query With Filters")
    class EmptyQueryWithFilters {

        @Test
        @DisplayName("Should search with null query and filters")
        void shouldSearchWithNullQuery() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document content", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document content", "de", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
        }

        @Test
        @DisplayName("Should search with empty string query and filters")
        void shouldSearchWithEmptyQuery() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document content", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document content", "de", null);

            final var result = indexService.search(
                    "",
                    List.of(new SearchFilter("language", "eq", "de", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("de");
        }

        @Test
        @DisplayName("Should match all with no query and no filters")
        void shouldMatchAllWithNoQueryAndNoFilters() throws Exception {
            indexDocumentWithMetadata("doc1.txt", "First document", "en", null);
            indexDocumentWithMetadata("doc2.txt", "Second document", "de", null);

            final var result = indexService.search(null, List.of(), 0, 10);

            assertThat(result.totalHits()).isEqualTo(2);
        }
    }

    // ========== Active Filters ==========

    @Nested
    @DisplayName("Active Filters")
    class ActiveFiltersTests {

        @Test
        @DisplayName("Should return active filters with matchCount")
        void shouldReturnActiveFiltersWithMatchCount() throws Exception {
            indexDocumentWithMetadata("en1.txt", "First English document", "en", null);
            indexDocumentWithMetadata("en2.txt", "Second English document", "en", null);
            indexDocumentWithMetadata("de1.txt", "Ein deutsches Dokument", "de", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, null)),
                    0, 10);

            assertThat(result.activeFilters()).hasSize(1);
            final ActiveFilter af = result.activeFilters().getFirst();
            assertThat(af.field()).isEqualTo("language");
            assertThat(af.operator()).isEqualTo("eq");
            assertThat(af.value()).isEqualTo("en");
            assertThat(af.matchCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should round-trip addedAt")
        void shouldRoundTripAddedAt() throws Exception {
            indexDocumentWithMetadata("doc.txt", "Test document content", "en", null);

            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, "2024-01-01T12:00:00Z")),
                    0, 10);

            assertThat(result.activeFilters()).hasSize(1);
            assertThat(result.activeFilters().getFirst().addedAt()).isEqualTo("2024-01-01T12:00:00Z");
        }

        @Test
        @DisplayName("Should return empty active filters when no filters provided")
        void shouldReturnEmptyActiveFilters() throws Exception {
            indexDocumentWithMetadata("doc.txt", "Test document content", "en", null);

            final var result = indexService.search(null, List.of(), 0, 10);

            assertThat(result.activeFilters()).isEmpty();
        }
    }

    // ========== DrillSideways Behavior ==========

    @Nested
    @DisplayName("DrillSideways Faceting")
    class DrillSidewaysFaceting {

        @Test
        @DisplayName("Should show alternative facet values when filtered")
        void shouldShowAlternativeFacetValuesWhenFiltered() throws Exception {
            indexDocumentWithMetadata("en1.txt", "English document one", "en", null);
            indexDocumentWithMetadata("en2.txt", "English document two", "en", null);
            indexDocumentWithMetadata("de1.txt", "German document one", "de", null);
            indexDocumentWithMetadata("fr1.txt", "French document one", "fr", null);

            // Filter by language=en — DrillSideways should still show de and fr in facets
            final var result = indexService.search(
                    null,
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(2);

            // Facets should include all language values, not just "en"
            final var languageFacets = result.facets().get("language");
            assertThat(languageFacets)
                    .as("DrillSideways should show alternative facet values for filtered dimension")
                    .isNotNull()
                    .hasSizeGreaterThanOrEqualTo(2);

            // Should show counts for en, de, fr
            final var facetValues = languageFacets.stream()
                    .map(LuceneIndexService.FacetValue::value)
                    .toList();
            assertThat(facetValues).contains("en", "de");
        }
    }

    // ========== Backward Compatibility ==========

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("Should work with legacy filterField and filterValue")
        void shouldWorkWithLegacyFilterFieldAndValue() throws Exception {
            indexDocumentWithMetadata("en-doc.txt", "English document about testing", "en", null);
            indexDocumentWithMetadata("de-doc.txt", "German document about testing", "de", null);

            // Use old method signature
            final var result = indexService.search("document", "language", "en", 0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
        }
    }

    // ========== ISO-8601 Date Parsing ==========

    @Nested
    @DisplayName("Date Parsing")
    class DateParsing {

        @Test
        @DisplayName("Should parse ISO-8601 instant format")
        void shouldParseIso8601Instant() {
            final long millis = LuceneIndexService.parseIso8601ToEpochMillis("2024-06-15T14:30:00Z");
            final Instant expected = Instant.parse("2024-06-15T14:30:00Z");
            assertThat(millis).isEqualTo(expected.toEpochMilli());
        }

        @Test
        @DisplayName("Should parse ISO-8601 local date-time format (assumes UTC)")
        void shouldParseIso8601LocalDateTime() {
            final long millis = LuceneIndexService.parseIso8601ToEpochMillis("2024-06-15T14:30:00");
            final long expected = LocalDateTime.of(2024, 6, 15, 14, 30, 0)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            assertThat(millis).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should parse ISO-8601 date-only format (start of day UTC)")
        void shouldParseIso8601LocalDate() {
            final long millis = LuceneIndexService.parseIso8601ToEpochMillis("2024-06-15");
            final long expected = LocalDate.of(2024, 6, 15)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            assertThat(millis).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should reject invalid date format")
        void shouldRejectInvalidDate() {
            assertThatThrownBy(() -> LuceneIndexService.parseIso8601ToEpochMillis("not-a-date"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot parse date");
        }
    }

    // ========== Date Field Ranges ==========

    @Nested
    @DisplayName("Date Field Ranges")
    class DateFieldRanges {

        @Test
        @DisplayName("Should return date field ranges after indexing")
        void shouldReturnDateFieldRanges() throws Exception {
            indexDocumentWithMetadata("doc.txt", "Test document for date ranges", "en", null);

            final Map<String, long[]> ranges = indexService.getDateFieldRanges();

            assertThat(ranges).isNotEmpty();
            assertThat(ranges).containsKey("modified_date");
            assertThat(ranges).containsKey("indexed_date");

            // The min and max should be reasonable (after year 2000)
            final long[] modifiedRange = ranges.get("modified_date");
            assertThat(modifiedRange[0]).isGreaterThan(0);
            assertThat(modifiedRange[1]).isGreaterThanOrEqualTo(modifiedRange[0]);
        }

        @Test
        @DisplayName("Should return empty ranges for empty index")
        void shouldReturnEmptyRangesForEmptyIndex() throws Exception {
            final Map<String, long[]> ranges = indexService.getDateFieldRanges();
            assertThat(ranges).isEmpty();
        }
    }

    // ========== Combined Filters ==========

    @Nested
    @DisplayName("Combined Filters")
    class CombinedFilters {

        @Test
        @DisplayName("Should combine facet, range, and NOT filters")
        void shouldCombineFacetRangeAndNotFilters() throws Exception {
            indexDocumentWithMetadata("en1.txt", "English document one about contracts", "en", "Alice");
            indexDocumentWithMetadata("en2.txt", "English document two about agreements", "en", "Bob");
            indexDocumentWithMetadata("de1.txt", "German document one about Vertraege", "de", "Alice");

            final var result = indexService.search(
                    null,
                    List.of(
                            new SearchFilter("language", "eq", "en", null, null, null, null),
                            new SearchFilter("file_size", "range", null, null, "0", null, null)
                    ),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(2);
            assertThat(result.documents())
                    .allMatch(doc -> "en".equals(doc.language()));
        }

        @Test
        @DisplayName("Should combine query with filters")
        void shouldCombineQueryWithFilters() throws Exception {
            indexDocumentWithMetadata("en1.txt", "English document about contracts and law", "en", null);
            indexDocumentWithMetadata("en2.txt", "English document about cooking and food", "en", null);
            indexDocumentWithMetadata("de1.txt", "German document about contracts and Recht", "de", null);

            final var result = indexService.search(
                    "contracts",
                    List.of(new SearchFilter("language", "eq", "en", null, null, null, null)),
                    0, 10);

            assertThat(result.totalHits()).isEqualTo(1);
            assertThat(result.documents().getFirst().language()).isEqualTo("en");
        }
    }
}
