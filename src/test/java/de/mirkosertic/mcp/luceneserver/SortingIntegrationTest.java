package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for sort-by-native-field functionality.
 * Verifies that documents indexed with SortedNumericDocValuesField can be retrieved in sorted order.
 */
@DisplayName("Sorting Integration Tests")
class SortingIntegrationTest {

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

    private void indexDocumentWithSize(final String fileName, final String content, final long fileSize)
            throws IOException {
        final Path testFile = docsDir.resolve(fileName);
        Files.writeString(testFile, content);

        final ExtractedDocument extracted = new ExtractedDocument(
                content, null, "en", "text/plain", fileSize);

        final var luceneDoc = documentIndexer.createDocument(testFile, extracted);
        documentIndexer.indexDocument(luceneDoc, extracted.content(), indexService);
        indexService.commit();
        indexService.refreshSearcher();
    }

    @Test
    @DisplayName("Should return documents sorted by file_size ascending")
    void shouldSortByFileSizeAscending() throws Exception {
        indexDocumentWithSize("large.txt", "alpha beta gamma search", 3000L);
        indexDocumentWithSize("small.txt", "alpha beta gamma search", 100L);
        indexDocumentWithSize("medium.txt", "alpha beta gamma search", 1500L);

        final LuceneIndexService.SearchResult result = indexService.search(
                "alpha", List.of(), 0, 10, "file_size", "asc");

        assertThat(result.documents()).hasSize(3);

        // Extract file_size values from results in order
        final List<Long> sizes = result.documents().stream()
                .map(SearchDocument::fileSize)
                .toList();

        // Ascending order: 100, 1500, 3000
        assertThat(sizes).containsExactly(100L, 1500L, 3000L);
    }

    @Test
    @DisplayName("Should return documents sorted by file_size descending")
    void shouldSortByFileSizeDescending() throws Exception {
        indexDocumentWithSize("large.txt", "alpha beta gamma search", 3000L);
        indexDocumentWithSize("small.txt", "alpha beta gamma search", 100L);
        indexDocumentWithSize("medium.txt", "alpha beta gamma search", 1500L);

        final LuceneIndexService.SearchResult result = indexService.search(
                "alpha", List.of(), 0, 10, "file_size", "desc");

        assertThat(result.documents()).hasSize(3);

        final List<Long> sizes = result.documents().stream()
                .map(SearchDocument::fileSize)
                .toList();

        // Descending order: 3000, 1500, 100
        assertThat(sizes).containsExactly(3000L, 1500L, 100L);
    }

    @Test
    @DisplayName("Should return documents sorted by modified_date ascending")
    void shouldSortByModifiedDateAscending() throws Exception {
        // Create files in order — sleep slightly between to ensure distinct modification times
        final Path file1 = docsDir.resolve("first.txt");
        final Path file2 = docsDir.resolve("second.txt");
        final Path file3 = docsDir.resolve("third.txt");
        Files.writeString(file1, "alpha search term");
        Thread.sleep(50);
        Files.writeString(file2, "alpha search term");
        Thread.sleep(50);
        Files.writeString(file3, "alpha search term");

        for (final Path f : List.of(file1, file2, file3)) {
            final ExtractedDocument extracted = new ExtractedDocument(
                    "alpha search term", null, "en", "text/plain", f.toFile().length());
            final var luceneDoc = documentIndexer.createDocument(f, extracted);
            documentIndexer.indexDocument(luceneDoc, extracted.content(), indexService);
            indexService.commit();
            indexService.refreshSearcher();
        }

        final LuceneIndexService.SearchResult result = indexService.search(
                "alpha", List.of(), 0, 10, "modified_date", "asc");

        assertThat(result.documents()).hasSize(3);

        // Verify that modified_dates are in non-decreasing order
        final List<Long> dates = result.documents().stream()
                .map(SearchDocument::modifiedDate)
                .toList();

        for (int i = 0; i < dates.size() - 1; i++) {
            assertThat(dates.get(i)).isLessThanOrEqualTo(dates.get(i + 1));
        }
    }

    @Test
    @DisplayName("Sort by unknown dbmeta_ field without DocValues falls back gracefully")
    void shouldFallbackGracefullyForUnknownSortField() throws Exception {
        indexDocumentWithSize("doc1.txt", "hello world", 500L);

        // Should not throw — falls back to score sorting with a warning
        final LuceneIndexService.SearchResult result = indexService.search(
                "hello", List.of(), 0, 10, "dbmeta_nonexistent", "asc");

        assertThat(result.documents()).hasSize(1);
    }
}
