package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Orchestrates document crawling, content extraction, and indexing.
 * Manages crawler lifecycle: start, pause, resume, and directory watching.
 * <p>
 * When reconciliation is enabled and a full reindex is <em>not</em> requested,
 * an incremental crawl is performed: only new or modified files are indexed,
 * and orphan documents (files that have been deleted from disk) are removed
 * from the index before the crawl begins.
 */
public class DocumentCrawlerService implements FileChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(DocumentCrawlerService.class);

    private final ApplicationConfig config;
    private final LuceneIndexService indexService;
    private final FileContentExtractor contentExtractor;
    private final DocumentIndexer documentIndexer;
    private final CrawlExecutorService crawlExecutor;
    private final CrawlStatisticsTracker statisticsTracker;
    private final DirectoryWatcherService watcherService;
    private final IndexReconciliationService reconciliationService;
    private final CrawlerConfigurationManager configManager;

    private final BlockingQueue<Document> batchQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean crawling = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    // Debounce support for file watcher events
    private final ConcurrentLinkedQueue<WatchEvent> pendingWatchEvents = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService watchCommitScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "watch-debounce");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private volatile Future<?> batchProcessorFuture;
    private volatile long originalNrtRefreshInterval;
    private volatile CrawlerState state = CrawlerState.IDLE;

    /**
     * When non-null, the incremental crawl is active and only files whose paths
     * are contained in this set should be processed.  {@code null} means all files
     * are processed (full crawl mode).
     */
    private volatile Set<String> filesToProcess;

    public DocumentCrawlerService(
            final ApplicationConfig config,
            final LuceneIndexService indexService,
            final FileContentExtractor contentExtractor,
            final DocumentIndexer documentIndexer,
            final CrawlExecutorService crawlExecutor,
            final CrawlStatisticsTracker statisticsTracker,
            final DirectoryWatcherService watcherService,
            final IndexReconciliationService reconciliationService,
            final CrawlerConfigurationManager configManager) {
        this.config = config;
        this.indexService = indexService;
        this.contentExtractor = contentExtractor;
        this.documentIndexer = documentIndexer;
        this.crawlExecutor = crawlExecutor;
        this.statisticsTracker = statisticsTracker;
        this.watcherService = watcherService;
        this.reconciliationService = reconciliationService;
        this.configManager = configManager;
    }

    /**
     * Initialize the crawler service. May trigger auto-crawl on startup.
     */
    public void init() {
        if (config.isCrawlOnStartup() && !config.getDirectories().isEmpty()) {
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

            if (config.getDirectories().isEmpty()) {
                logger.warn("No directories configured for crawling");
                crawling.set(false);
                state = CrawlerState.IDLE;
                return;
            }

            // Determine crawl mode and perform reconciliation if appropriate
            final boolean useIncrementalCrawl = !fullReindex && config.isReconciliationEnabled();
            final ReconciliationResult reconciliationResult;

            if (useIncrementalCrawl) {
                reconciliationResult = performReconciliation();
            } else {
                reconciliationResult = null;
            }

            // If reconciliation failed (returned null) we fall back to full crawl behaviour
            final boolean effectiveFullReindex = fullReindex || (useIncrementalCrawl && reconciliationResult == null);

            logger.info("Starting crawl with fullReindex={}, effectiveFullReindex={}, incrementalMode={}",
                    fullReindex, effectiveFullReindex, reconciliationResult != null);

            // Clear index if full reindex is in effect
            if (effectiveFullReindex) {
                logger.info("Performing full reindex - clearing existing index");
                indexService.getIndexWriter().deleteAll();
                indexService.commit();
                filesToProcess = null; // process all files
            } else {
                // Incremental mode: restrict crawl to only ADD + UPDATE files
                filesToProcess = new HashSet<>(reconciliationResult.filesToAdd());
                filesToProcess.addAll(reconciliationResult.filesToUpdate());

                // Apply orphan deletions before we start indexing new content
                try {
                    reconciliationService.applyDeletions(reconciliationResult.filesToDelete());
                } catch (final IOException e) {
                    logger.error("Failed to apply orphan deletions, falling back to full crawl", e);
                    // Fall back: clear the filter and do a full re-crawl
                    filesToProcess = null;
                    indexService.getIndexWriter().deleteAll();
                    indexService.commit();
                }
            }

            // Reset statistics and set crawl mode
            statisticsTracker.reset();
            final String crawlMode = (reconciliationResult != null && filesToProcess != null) ? "incremental" : "full";
            statisticsTracker.setCrawlMode(crawlMode);

            // Record reconciliation stats if available
            if (reconciliationResult != null) {
                statisticsTracker.setOrphansDeleted(reconciliationResult.filesToDelete().size());
                statisticsTracker.setFilesSkippedUnchanged(reconciliationResult.unchangedCount());
                statisticsTracker.setReconciliationTimeMs(reconciliationResult.reconciliationTimeMs());
            }

            statisticsTracker.sendStartNotification(config.getDirectories().size());

            // Start batch processor
            batchProcessorFuture = crawlExecutor.submit(this::processBatches);

            // Determine total files for bulk indexing decision without a separate filesystem walk.
            // For incremental crawls, the reconciliation result already knows the exact count.
            // For full reindex, always enable bulk mode — no concurrent searches are expected.
            final long totalFiles;
            if (reconciliationResult != null && filesToProcess != null) {
                totalFiles = filesToProcess.size();
            } else {
                totalFiles = config.getBulkIndexThreshold();
            }
            logger.info("Estimated {} files to process (crawlMode={})", totalFiles, crawlMode);

            // Adjust NRT refresh interval if bulk indexing
            if (totalFiles >= config.getBulkIndexThreshold()) {
                logger.info("Bulk indexing mode: slowing NRT refresh to {}ms",
                        config.getSlowNrtRefreshIntervalMs());
                originalNrtRefreshInterval = 100; // Default value
                indexService.setNrtRefreshInterval(config.getSlowNrtRefreshIntervalMs());
            }

            // Crawl each directory in parallel
            final List<Future<?>> futures = new ArrayList<>();
            for (final String directory : config.getDirectories()) {
                final Future<?> future = crawlExecutor.submit(() -> crawlDirectory(directory));
                futures.add(future);
            }

            // Capture for use in the completion lambda
            final long totalFilesCaptured = totalFiles;
            final String crawlModeCaptured = crawlMode;

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
                    if (totalFilesCaptured >= config.getBulkIndexThreshold()) {
                        indexService.setNrtRefreshInterval(originalNrtRefreshInterval);
                        logger.info("Restored NRT refresh interval to {}ms", originalNrtRefreshInterval);
                    }

                    // Final commit
                    indexService.commit();

                    // Send completion notification
                    statisticsTracker.sendCompleteNotification();

                    // Persist crawl state on successful completion
                    saveCrawlStateOnSuccess(crawlModeCaptured);

                    // Clear the incremental filter
                    filesToProcess = null;

                    // Setup directory watching if enabled
                    if (config.isWatchEnabled()) {
                        setupWatchers();
                        state = CrawlerState.WATCHING;
                    } else {
                        state = CrawlerState.IDLE;
                    }

                } catch (final Exception e) {
                    logger.error("Error during crawl completion", e);
                    crawling.set(false);
                    filesToProcess = null;
                    state = CrawlerState.IDLE;
                }
            });

        } else {
            logger.warn("Crawl already in progress");
        }
    }

    /**
     * Execute the reconciliation phase: notify, reconcile, notify again.
     * Returns {@code null} on any failure so the caller can fall back to a full crawl.
     */
    private ReconciliationResult performReconciliation() {
        statisticsTracker.sendReconciliationStartNotification();
        try {
            final ReconciliationResult result = reconciliationService.reconcile(
                    config.getDirectories(),
                    config.getIncludePatterns(),
                    config.getExcludePatterns()
            );
            statisticsTracker.sendReconciliationCompleteNotification(result);
            return result;
        } catch (final IOException e) {
            logger.error("Reconciliation failed -- will fall back to full crawl", e);
            return null;
        }
    }

    /**
     * Persist the crawl state after a successful crawl completion.
     */
    private void saveCrawlStateOnSuccess(final String crawlMode) {
        try {
            final long docCount = indexService.getDocumentCount();
            final CrawlState crawlState = new CrawlState(System.currentTimeMillis(), docCount, crawlMode);
            configManager.saveCrawlState(crawlState);
        } catch (final IOException e) {
            logger.error("Failed to persist crawl state", e);
        }
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
                config.getIncludePatterns(),
                config.getExcludePatterns()
        );

        try (final Stream<Path> paths = Files.walk(dirPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(matcher::shouldInclude)
                    .filter(this::shouldProcessFile)
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

    /**
     * Determine whether a given file should be processed in the current crawl.
     * In incremental mode, only files present in the {@link #filesToProcess} set
     * are processed.  In full mode ({@code filesToProcess == null}), all files pass.
     */
    private boolean shouldProcessFile(final Path file) {
        final Set<String> toProcess = filesToProcess;
        if (toProcess == null) {
            return true; // full crawl -- process everything
        }
        return toProcess.contains(file.toString());
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
        final List<Document> batch = new ArrayList<>(config.getBatchSize());
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
                if (batch.size() >= config.getBatchSize() ||
                        (timeSinceLastBatch >= config.getBatchTimeoutMs() && !batch.isEmpty())) {

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
        for (final String directory : config.getDirectories()) {
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
                config.getIncludePatterns(),
                config.getExcludePatterns()
        );

        if (matcher.shouldInclude(file)) {
            pendingWatchEvents.add(new WatchEvent(file, WatchEventType.CREATE_OR_MODIFY));
            scheduleFlush();
        }
    }

    @Override
    public void onFileModified(final Path file) {
        logger.debug("File modified: {}", file);
        final FilePatternMatcher matcher = new FilePatternMatcher(
                config.getIncludePatterns(),
                config.getExcludePatterns()
        );

        if (matcher.shouldInclude(file)) {
            pendingWatchEvents.add(new WatchEvent(file, WatchEventType.CREATE_OR_MODIFY));
            scheduleFlush();
        }
    }

    @Override
    public void onFileDeleted(final Path file) {
        logger.debug("File deleted: {}", file);
        pendingWatchEvents.add(new WatchEvent(file, WatchEventType.DELETE));
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            watchCommitScheduler.schedule(this::flushWatchEvents,
                    config.getWatchDebounceMs(), TimeUnit.MILLISECONDS);
        }
    }

    void flushWatchEvents() {
        try {
            // Drain and deduplicate: last event per path wins
            final Map<String, WatchEvent> deduplicated = new LinkedHashMap<>();
            WatchEvent event;
            while ((event = pendingWatchEvents.poll()) != null) {
                deduplicated.put(event.file().toString(), event);
            }

            if (deduplicated.isEmpty()) {
                return;
            }

            logger.debug("Flushing {} deduplicated watch events", deduplicated.size());

            for (final WatchEvent evt : deduplicated.values()) {
                if (evt.type() == WatchEventType.DELETE) {
                    try {
                        documentIndexer.deleteDocument(indexService.getIndexWriter(), evt.file().toString());
                        logger.info("Deleted file from index: {}", evt.file());
                    } catch (final IOException e) {
                        logger.error("Error deleting file from index: {}", evt.file(), e);
                    }
                } else {
                    try {
                        final ExtractedDocument extracted = contentExtractor.extract(evt.file());
                        final Document document = documentIndexer.createDocument(evt.file(), extracted);
                        documentIndexer.indexDocument(indexService.getIndexWriter(), document);
                        logger.info("Indexed file: {}", evt.file());
                    } catch (final Exception e) {
                        logger.error("Error indexing file: {}", evt.file(), e);
                    }
                }
            }

            indexService.commit();
        } catch (final IOException e) {
            logger.error("Error committing watch events", e);
        } finally {
            flushScheduled.set(false);
            // If new events arrived during flush, schedule another flush
            if (!pendingWatchEvents.isEmpty()) {
                scheduleFlush();
            }
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

        // Update config
        config.setDirectories(new ArrayList<>(newDirectories));

        // Restart watchers if needed
        if (state == CrawlerState.WATCHING && !newDirectories.isEmpty()) {
            setupWatchers();
            state = CrawlerState.WATCHING;
        }

        logger.info("Directories updated successfully");
    }

    /**
     * Shutdown the crawler service. Should be called on application shutdown.
     */
    public void shutdown() {
        logger.info("Shutting down DocumentCrawlerService");
        crawling.set(false);

        // Stop watchers
        try {
            watcherService.stopAll();
        } catch (final Exception e) {
            logger.error("Error stopping watchers", e);
        }

        // Shut down watch debounce scheduler and flush remaining events
        watchCommitScheduler.shutdown();
        try {
            if (!watchCommitScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                watchCommitScheduler.shutdownNow();
            }
        } catch (final InterruptedException e) {
            watchCommitScheduler.shutdownNow();
            Thread.currentThread().interrupt();
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

    record WatchEvent(Path file, WatchEventType type) {}

    enum WatchEventType { CREATE_OR_MODIFY, DELETE }

    public enum CrawlerState {
        IDLE,
        CRAWLING,
        PAUSED,
        WATCHING
    }
}
