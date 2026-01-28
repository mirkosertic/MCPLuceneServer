package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.LuceneIndexService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class DocumentCrawlerService implements FileChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(DocumentCrawlerService.class);

    private final CrawlerProperties properties;
    private final LuceneIndexService indexService;
    private final FileContentExtractor contentExtractor;
    private final DocumentIndexer documentIndexer;
    private final CrawlExecutorService crawlExecutor;
    private final CrawlStatisticsTracker statisticsTracker;
    private final DirectoryWatcherService watcherService;

    private final BlockingQueue<Document> batchQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean crawling = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Future<?> batchProcessorFuture;
    private volatile long originalNrtRefreshInterval;
    private volatile CrawlerState state = CrawlerState.IDLE;

    public DocumentCrawlerService(
            final CrawlerProperties properties,
            final LuceneIndexService indexService,
            final FileContentExtractor contentExtractor,
            final DocumentIndexer documentIndexer,
            final CrawlExecutorService crawlExecutor,
            final CrawlStatisticsTracker statisticsTracker,
            final DirectoryWatcherService watcherService) {
        this.properties = properties;
        this.indexService = indexService;
        this.contentExtractor = contentExtractor;
        this.documentIndexer = documentIndexer;
        this.crawlExecutor = crawlExecutor;
        this.statisticsTracker = statisticsTracker;
        this.watcherService = watcherService;
    }

    @PostConstruct
    public void init() {
        if (properties.isCrawlOnStartup() && !properties.getDirectories().isEmpty()) {
            logger.info("Auto-crawl on startup is enabled");
            crawlExecutor.execute(() -> {
                try {
                    Thread.sleep(2000); // Give the system time to fully initialize
                    startCrawl(false);
                } catch (final Exception e) {
                    logger.error("Failed to start auto-crawl", e);
                }
            });
        }
    }

    public void startCrawl(final boolean fullReindex) throws IOException {
        if (crawling.compareAndSet(false, true)) {
            state = CrawlerState.CRAWLING;
            paused.set(false);

            if (properties.getDirectories().isEmpty()) {
                logger.warn("No directories configured for crawling");
                crawling.set(false);
                state = CrawlerState.IDLE;
                return;
            }

            logger.info("Starting crawl with fullReindex={}", fullReindex);

            // Clear index if full reindex requested
            if (fullReindex) {
                logger.info("Performing full reindex - clearing existing index");
                indexService.getIndexWriter().deleteAll();
                indexService.commit();
            }

            // Reset statistics
            statisticsTracker.reset();
            statisticsTracker.sendStartNotification(properties.getDirectories().size());

            // Start batch processor
            batchProcessorFuture = crawlExecutor.submit(this::processBatches);

            // Count total files first to determine if we need bulk optimization
            final long totalFiles = countTotalFiles();
            logger.info("Found approximately {} files to process", totalFiles);

            // Adjust NRT refresh interval if bulk indexing
            if (totalFiles >= properties.getBulkIndexThreshold()) {
                logger.info("Bulk indexing mode: slowing NRT refresh to {}ms",
                        properties.getSlowNrtRefreshIntervalMs());
                originalNrtRefreshInterval = 100; // Default value
                indexService.setNrtRefreshInterval(properties.getSlowNrtRefreshIntervalMs());
            }

            // Crawl each directory in parallel
            final List<Future<?>> futures = new ArrayList<>();
            for (final String directory : properties.getDirectories()) {
                final Future<?> future = crawlExecutor.submit(() -> crawlDirectory(directory));
                futures.add(future);
            }

            // Wait for all crawl tasks to complete
            crawlExecutor.execute(() -> {
                try {
                    for (final Future<?> future : futures) {
                        future.get();
                    }

                    // Signal batch processor to finish
                    crawling.set(false);

                    // Wait for batch processor to complete
                    if (batchProcessorFuture != null) {
                        batchProcessorFuture.get();
                    }

                    // Restore original NRT refresh interval
                    if (totalFiles >= properties.getBulkIndexThreshold()) {
                        indexService.setNrtRefreshInterval(originalNrtRefreshInterval);
                        logger.info("Restored NRT refresh interval to {}ms", originalNrtRefreshInterval);
                    }

                    // Final commit
                    indexService.commit();

                    // Send completion notification
                    statisticsTracker.sendCompleteNotification();

                    // Setup directory watching if enabled
                    if (properties.isWatchEnabled()) {
                        setupWatchers();
                        state = CrawlerState.WATCHING;
                    } else {
                        state = CrawlerState.IDLE;
                    }

                } catch (final Exception e) {
                    logger.error("Error during crawl completion", e);
                    crawling.set(false);
                    state = CrawlerState.IDLE;
                }
            });

        } else {
            logger.warn("Crawl already in progress");
        }
    }

    private long countTotalFiles() {
        long count = 0;
        final FilePatternMatcher matcher = new FilePatternMatcher(
                properties.getIncludePatterns(),
                properties.getExcludePatterns()
        );

        for (final String directory : properties.getDirectories()) {
            final Path dirPath = Paths.get(directory);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                continue;
            }

            try (final Stream<Path> paths = Files.walk(dirPath)) {
                count += paths
                        .filter(Files::isRegularFile)
                        .filter(matcher::shouldInclude)
                        .count();
            } catch (final IOException e) {
                logger.warn("Error counting files in directory: {}", directory, e);
            }
        }

        return count;
    }

    private void crawlDirectory(final String directory) {
        logger.info("Crawling directory: {}", directory);
        final Path dirPath = Paths.get(directory);

        if (!Files.exists(dirPath)) {
            logger.warn("Directory does not exist: {}", directory);
            return;
        }

        if (!Files.isDirectory(dirPath)) {
            logger.warn("Path is not a directory: {}", directory);
            return;
        }

        final FilePatternMatcher matcher = new FilePatternMatcher(
                properties.getIncludePatterns(),
                properties.getExcludePatterns()
        );

        try (final Stream<Path> paths = Files.walk(dirPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(matcher::shouldInclude)
                    .forEach(file -> {
                        // Check if paused
                        while (paused.get() && crawling.get()) {
                            try {
                                Thread.sleep(100);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }

                        if (!crawling.get()) {
                            return;
                        }

                        statisticsTracker.incrementFilesFound(directory);
                        processFile(file, directory);
                    });
        } catch (final IOException e) {
            logger.error("Error crawling directory: {}", directory, e);
        }

        logger.info("Finished crawling directory: {}", directory);
    }

    private void processFile(final Path file, final String directory) {
        try {
            // Extract content
            final ExtractedDocument extracted = contentExtractor.extract(file);

            // Create Lucene document
            final Document document = documentIndexer.createDocument(file, extracted);

            // Add to batch queue
            batchQueue.put(document);

            // Update statistics
            statisticsTracker.incrementFilesProcessed(directory, extracted.fileSize());

        } catch (final Exception e) {
            logger.error("Error processing file: {}", file, e);
            statisticsTracker.incrementFilesFailed(directory);
        }
    }

    private void processBatches() {
        final List<Document> batch = new ArrayList<>(properties.getBatchSize());
        long lastBatchTime = System.currentTimeMillis();

        while (crawling.get() || !batchQueue.isEmpty()) {
            try {
                // Poll for documents with timeout
                final Document doc = batchQueue.poll(100, TimeUnit.MILLISECONDS);

                if (doc != null) {
                    batch.add(doc);
                }

                // Check if we should process the batch
                final long timeSinceLastBatch = System.currentTimeMillis() - lastBatchTime;
                if (batch.size() >= properties.getBatchSize() ||
                        (timeSinceLastBatch >= properties.getBatchTimeoutMs() && !batch.isEmpty())) {

                    // Index the batch
                    indexBatch(batch);

                    // Clear batch and reset timer
                    batch.clear();
                    lastBatchTime = System.currentTimeMillis();
                }

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Batch processor interrupted");
                break;
            } catch (final Exception e) {
                logger.error("Error processing batch", e);
            }
        }

        // Process any remaining documents
        if (!batch.isEmpty()) {
            try {
                indexBatch(batch);
            } catch (final Exception e) {
                logger.error("Error processing final batch", e);
            }
        }

        logger.info("Batch processor finished");
    }

    private void indexBatch(final List<Document> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }

        for (final Document doc : batch) {
            documentIndexer.indexDocument(indexService.getIndexWriter(), doc);
            final String directory = doc.get("file_path");
            if (directory != null) {
                // Extract directory from file path for statistics
                final Path path = Paths.get(directory);
                final String dir = path.getParent().toString();
                statisticsTracker.incrementFilesIndexed(dir);
            }
        }

        indexService.commit();
        logger.debug("Indexed batch of {} documents", batch.size());
    }

    private void setupWatchers() {
        logger.info("Setting up directory watchers");
        for (final String directory : properties.getDirectories()) {
            try {
                watcherService.watchDirectory(Paths.get(directory), this);
                logger.info("Watching directory: {}", directory);
            } catch (final IOException e) {
                logger.error("Failed to setup watcher for directory: {}", directory, e);
            }
        }
    }

    @Override
    public void onFileCreated(final Path file) {
        logger.debug("File created: {}", file);
        final FilePatternMatcher matcher = new FilePatternMatcher(
                properties.getIncludePatterns(),
                properties.getExcludePatterns()
        );

        if (matcher.shouldInclude(file)) {
            try {
                final ExtractedDocument extracted = contentExtractor.extract(file);
                final Document document = documentIndexer.createDocument(file, extracted);
                documentIndexer.indexDocument(indexService.getIndexWriter(), document);
                indexService.commit();
                logger.info("Indexed new file: {}", file);
            } catch (final Exception e) {
                logger.error("Error indexing new file: {}", file, e);
            }
        }
    }

    @Override
    public void onFileModified(final Path file) {
        logger.debug("File modified: {}", file);
        onFileCreated(file); // Reindex modified files
    }

    @Override
    public void onFileDeleted(final Path file) {
        logger.debug("File deleted: {}", file);
        try {
            documentIndexer.deleteDocument(indexService.getIndexWriter(), file.toString());
            indexService.commit();
            logger.info("Deleted file from index: {}", file);
        } catch (final Exception e) {
            logger.error("Error deleting file from index: {}", file, e);
        }
    }

    public void pauseCrawler() {
        if (crawling.get() && paused.compareAndSet(false, true)) {
            state = CrawlerState.PAUSED;
            statisticsTracker.sendPauseNotification();
            logger.info("Crawler paused");
        }
    }

    public void resumeCrawler() {
        if (crawling.get() && paused.compareAndSet(true, false)) {
            state = CrawlerState.CRAWLING;
            statisticsTracker.sendResumeNotification();
            logger.info("Crawler resumed");
        }
    }

    public CrawlerState getState() {
        return state;
    }

    public CrawlStatistics getStatistics() {
        return statisticsTracker.getStatistics();
    }

    public synchronized void updateDirectories(final List<String> newDirectories) throws IOException {
        logger.info("Updating crawler directories to: {}", newDirectories);

        // Stop existing watchers if in WATCHING state
        if (state == CrawlerState.WATCHING) {
            watcherService.stopAll();
        }

        // Update properties
        properties.setDirectories(new ArrayList<>(newDirectories));

        // Restart watchers if needed
        if (state == CrawlerState.WATCHING && !newDirectories.isEmpty()) {
            setupWatchers();
            state = CrawlerState.WATCHING;
        }

        logger.info("Directories updated successfully");
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down DocumentCrawlerService");
        crawling.set(false);

        // Stop watchers
        try {
            watcherService.stopAll();
        } catch (final Exception e) {
            logger.error("Error stopping watchers", e);
        }

        // Wait for batch processor to finish
        if (batchProcessorFuture != null) {
            try {
                batchProcessorFuture.get(10, TimeUnit.SECONDS);
            } catch (final Exception e) {
                logger.warn("Batch processor did not finish in time", e);
            }
        }
    }

    public enum CrawlerState {
        IDLE,
        CRAWLING,
        PAUSED,
        WATCHING
    }
}
