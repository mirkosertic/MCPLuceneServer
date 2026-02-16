package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the debounced file watcher event processing in DocumentCrawlerService.
 */
@DisplayName("DocumentCrawlerService Watch Debounce Tests")
class DocumentCrawlerServiceWatchDebounceTest {

    private ApplicationConfig config;
    private LuceneIndexService indexService;
    private FileContentExtractor contentExtractor;
    private DocumentIndexer documentIndexer;
    private IndexWriter indexWriter;
    private DocumentCrawlerService crawlerService;

    @BeforeEach
    void setUp() throws IOException {
        config = mock(ApplicationConfig.class);
        indexService = mock(LuceneIndexService.class);
        contentExtractor = mock(FileContentExtractor.class);
        documentIndexer = mock(DocumentIndexer.class);
        indexWriter = mock(IndexWriter.class);

        when(config.getIncludePatterns()).thenReturn(List.of("*.txt", "*.pdf"));
        when(config.getExcludePatterns()).thenReturn(List.of());
        when(config.getWatchDebounceMs()).thenReturn(200L);
        when(config.isWatchEnabled()).thenReturn(false);
        when(config.getDirectories()).thenReturn(List.of());
        when(indexService.getIndexWriter()).thenReturn(indexWriter);

        final CrawlExecutorService crawlExecutor = mock(CrawlExecutorService.class);
        final CrawlStatisticsTracker statisticsTracker = mock(CrawlStatisticsTracker.class);
        final DirectoryWatcherService watcherService = mock(DirectoryWatcherService.class);
        final IndexReconciliationService reconciliationService = mock(IndexReconciliationService.class);
        final CrawlerConfigurationManager configManager = mock(CrawlerConfigurationManager.class);

        crawlerService = new DocumentCrawlerService(
                config, indexService, contentExtractor, documentIndexer,
                crawlExecutor, statisticsTracker, watcherService,
                reconciliationService, configManager
        );
    }

    @Test
    @DisplayName("Should deduplicate multiple modifications of same file into single index operation")
    void shouldDeduplicateMultipleModifications() throws Exception {
        final Path file = Path.of("/test/document.txt");
        final ExtractedDocument extracted = new ExtractedDocument("content", java.util.Map.of(), "en", "text/plain", 100);
        when(contentExtractor.extract(file)).thenReturn(extracted);
        when(documentIndexer.createDocument(eq(file), eq(extracted))).thenReturn(new Document());

        // Fire 5 rapid modifications
        for (int i = 0; i < 5; i++) {
            crawlerService.onFileModified(file);
        }

        // Wait for debounce flush
        verify(contentExtractor, timeout(2000).times(1)).extract(file);
        verify(indexService, timeout(2000).times(1)).commit();
    }

    @Test
    @DisplayName("Should process DELETE after CREATE for same path as DELETE only")
    void shouldApplyDeleteAfterCreate() throws Exception {
        final Path file = Path.of("/test/document.txt");
        final ExtractedDocument extracted = new ExtractedDocument("content", java.util.Map.of(), "en", "text/plain", 100);
        when(contentExtractor.extract(file)).thenReturn(extracted);
        when(documentIndexer.createDocument(eq(file), eq(extracted))).thenReturn(new Document());

        // Create then immediately delete
        crawlerService.onFileCreated(file);
        crawlerService.onFileDeleted(file);

        // Wait for debounce flush
        verify(documentIndexer, timeout(2000).times(1)).deleteDocument(indexWriter, file.toString());
        // Should not have extracted/indexed since DELETE wins
        verify(contentExtractor, never()).extract(file);
        verify(indexService, timeout(2000).times(1)).commit();
    }

    @Test
    @DisplayName("Should batch multiple different files into single commit")
    void shouldBatchMultipleFilesIntoSingleCommit() throws Exception {
        final Path file1 = Path.of("/test/doc1.txt");
        final Path file2 = Path.of("/test/doc2.txt");
        final Path file3 = Path.of("/test/doc3.txt");

        final ExtractedDocument extracted1 = new ExtractedDocument("content1", java.util.Map.of(), "en", "text/plain", 100);
        final ExtractedDocument extracted2 = new ExtractedDocument("content2", java.util.Map.of(), "en", "text/plain", 100);
        final ExtractedDocument extracted3 = new ExtractedDocument("content3", java.util.Map.of(), "en", "text/plain", 100);

        when(contentExtractor.extract(file1)).thenReturn(extracted1);
        when(contentExtractor.extract(file2)).thenReturn(extracted2);
        when(contentExtractor.extract(file3)).thenReturn(extracted3);
        when(documentIndexer.createDocument(eq(file1), eq(extracted1))).thenReturn(new Document());
        when(documentIndexer.createDocument(eq(file2), eq(extracted2))).thenReturn(new Document());
        when(documentIndexer.createDocument(eq(file3), eq(extracted3))).thenReturn(new Document());

        // Fire events for 3 different files rapidly
        crawlerService.onFileCreated(file1);
        crawlerService.onFileModified(file2);
        crawlerService.onFileCreated(file3);

        // Wait for debounce flush — should result in single commit
        verify(contentExtractor, timeout(2000).times(1)).extract(file1);
        verify(contentExtractor, timeout(2000).times(1)).extract(file2);
        verify(contentExtractor, timeout(2000).times(1)).extract(file3);
        verify(indexService, timeout(2000).times(1)).commit();
    }

    @Test
    @DisplayName("Should handle mixed create/modify/delete events correctly")
    void shouldHandleMixedEvents() throws Exception {
        final Path file1 = Path.of("/test/doc1.txt");
        final Path file2 = Path.of("/test/doc2.txt");

        final ExtractedDocument extracted1 = new ExtractedDocument("content1", java.util.Map.of(), "en", "text/plain", 100);
        when(contentExtractor.extract(file1)).thenReturn(extracted1);
        when(documentIndexer.createDocument(eq(file1), eq(extracted1))).thenReturn(new Document());

        // Create file1, delete file2
        crawlerService.onFileCreated(file1);
        crawlerService.onFileDeleted(file2);

        // Wait for flush
        verify(contentExtractor, timeout(2000).times(1)).extract(file1);
        verify(documentIndexer, timeout(2000).times(1)).deleteDocument(indexWriter, file2.toString());
        verify(indexService, timeout(2000).times(1)).commit();
    }

    @Test
    @DisplayName("Should not process events immediately but after debounce delay")
    void shouldDelayProcessingByDebounceMs() throws Exception {
        final Path file = Path.of("/test/document.txt");
        final ExtractedDocument extracted = new ExtractedDocument("content", java.util.Map.of(), "en", "text/plain", 100);
        when(contentExtractor.extract(file)).thenReturn(extracted);
        when(documentIndexer.createDocument(eq(file), eq(extracted))).thenReturn(new Document());

        crawlerService.onFileCreated(file);

        // Should not have been processed immediately (debounce is 200ms)
        verify(contentExtractor, after(50).never()).extract(file);

        // But should be processed within debounce window + margin
        verify(contentExtractor, timeout(2000).times(1)).extract(file);
    }

    @Test
    @DisplayName("Should exclude files not matching include patterns")
    void shouldExcludeNonMatchingFiles() throws Exception {
        final Path file = Path.of("/test/document.java");

        crawlerService.onFileCreated(file);

        // .java doesn't match *.txt or *.pdf patterns
        Thread.sleep(500);
        verify(contentExtractor, never()).extract(any());
    }
}
