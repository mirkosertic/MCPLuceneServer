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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private volatile Thread coordinatorThread;
    private volatile ScheduledExecutorService commitTimer;
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
            statisticsTracker.startPeriodicNotifications();

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

            // Start periodic commit timer
            startCommitTimer();

            final long totalFilesCaptured = totalFiles;
            final String crawlModeCaptured = crawlMode;

            final Thread coordinator = new Thread(() -> {
                try {
                    final List<Future<?>> fileFutures = new ArrayList<>();
                    for (final String directory : config.getDirectories()) {
                        submitFileTasks(directory, fileFutures);
                    }
                    // Wait for all file processing to complete
                    for (final Future<?> future : fileFutures) {
                        try {
                            future.get();
                        } catch (final ExecutionException e) {
                            logger.error("Error in file processing task", e.getCause());
                        }
                    }
                    onCrawlComplete(totalFilesCaptured, crawlModeCaptured);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Crawl coordinator interrupted");
                    onCrawlFailed();
                } catch (final Exception e) {
                    logger.error("Error during crawl coordination", e);
                    onCrawlFailed();
                }
            }, "crawl-coordinator");
            coordinator.setDaemon(true);
            coordinatorThread = coordinator;
            coordinator.start();

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

    private void submitFileTasks(final String directory, final List<Future<?>> futures) {
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
                        if (!crawling.get()) {
                            return;
                        }
                        statisticsTracker.incrementFilesFound(directory);
                        futures.add(crawlExecutor.submit(() -> processFile(file, directory)));
                    });
        } catch (final IOException e) {
            logger.error("Error crawling directory: {}", directory, e);
        }

        logger.info("Finished submitting tasks for directory: {}", directory);
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

    /**
     * Returns {@code true} if the file exists and has zero bytes.
     * Returns {@code false} if the file does not exist or its size cannot be determined.
     */
    private static boolean isEmptyFile(final Path file) {
        try {
            return Files.exists(file) && Files.size(file) == 0;
        } catch (final IOException e) {
            return false;
        }
    }

    private void processFile(final Path file, final String directory) {
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

        final String filePath = file.toString();
        statisticsTracker.registerActiveFile(filePath);
        try {
            if (isEmptyFile(file)) {
                logger.debug("Skipping empty file (0 bytes): {}", file);
                documentIndexer.deleteDocument(indexService.getIndexWriter(), filePath);
                statisticsTracker.incrementFilesProcessed(directory, 0);
                return;
            }
            final ExtractedDocument extracted = contentExtractor.extract(file);
            if (extracted.content() == null || extracted.content().isBlank()) {
                logger.debug("Skipping file with no extractable content: {}", file);
                documentIndexer.deleteDocument(indexService.getIndexWriter(), filePath);
                statisticsTracker.incrementFilesProcessed(directory, extracted.fileSize());
                return;
            }
            final Document document = documentIndexer.createDocument(file, extracted);
            documentIndexer.indexDocument(indexService.getIndexWriter(), document);
            statisticsTracker.incrementFilesProcessed(directory, extracted.fileSize());
            statisticsTracker.incrementFilesIndexed(directory);
        } catch (final Exception e) {
            logger.error("Error processing file: {}", file, e);
            statisticsTracker.incrementFilesFailed(directory);
        } finally {
            statisticsTracker.unregisterActiveFile(filePath);
        }
    }

    private void startCommitTimer() {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "commit-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> {
            try {
                indexService.commit();
            } catch (final IOException e) {
                logger.error("Error during periodic commit", e);
            }
        }, config.getBatchTimeoutMs(), config.getBatchTimeoutMs(), TimeUnit.MILLISECONDS);
        this.commitTimer = timer;
    }

    private void stopCommitTimer() {
        final ScheduledExecutorService timer = this.commitTimer;
        if (timer != null) {
            timer.shutdown();
            this.commitTimer = null;
        }
    }

    private void onCrawlComplete(final long totalFiles, final String crawlMode) {
        try {
            // Stop commit timer before final commit
            stopCommitTimer();

            // Restore original NRT refresh interval
            if (totalFiles >= config.getBulkIndexThreshold()) {
                indexService.setNrtRefreshInterval(originalNrtRefreshInterval);
                logger.info("Restored NRT refresh interval to {}ms", originalNrtRefreshInterval);
            }

            // Final commit
            indexService.commit();

            // Stop periodic progress notifications before sending completion
            statisticsTracker.stopPeriodicNotifications();

            // Send completion notification
            statisticsTracker.sendCompleteNotification();

            // Persist crawl state on successful completion
            saveCrawlStateOnSuccess(crawlMode);

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
            onCrawlFailed();
        } finally {
            crawling.set(false);
        }
    }

    private void onCrawlFailed() {
        stopCommitTimer();
        statisticsTracker.stopPeriodicNotifications();
        crawling.set(false);
        filesToProcess = null;
        state = CrawlerState.IDLE;
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
                        if (isEmptyFile(evt.file())) {
                            logger.debug("Skipping empty file (0 bytes): {}", evt.file());
                            documentIndexer.deleteDocument(indexService.getIndexWriter(), evt.file().toString());
                            continue;
                        }
                        final ExtractedDocument extracted = contentExtractor.extract(evt.file());
                        if (extracted.content() == null || extracted.content().isBlank()) {
                            logger.debug("Skipping file with no extractable content: {}", evt.file());
                            documentIndexer.deleteDocument(indexService.getIndexWriter(), evt.file().toString());
                        } else {
                            final Document document = documentIndexer.createDocument(evt.file(), extracted);
                            documentIndexer.indexDocument(indexService.getIndexWriter(), document);
                            logger.info("Indexed file: {}", evt.file());
                        }
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

        // Shut down statistics tracker (progress timer)
        statisticsTracker.shutdown();

        // Stop commit timer
        stopCommitTimer();

        // Wait for coordinator thread to finish
        final Thread coord = coordinatorThread;
        if (coord != null) {
            try {
                coord.join(10000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for coordinator thread");
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
