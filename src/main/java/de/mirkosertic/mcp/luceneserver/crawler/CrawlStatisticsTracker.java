package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.NotificationService;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks crawler progress statistics and sends notifications.
 * Thread-safe for use from multiple crawler threads.
 */
public class CrawlStatisticsTracker {

    private static final Logger logger = LoggerFactory.getLogger(CrawlStatisticsTracker.class);

    private final ApplicationConfig config;
    private final NotificationService notificationService;

    private final AtomicLong filesFound = new AtomicLong(0);
    private final AtomicLong filesProcessed = new AtomicLong(0);
    private final AtomicLong filesIndexed = new AtomicLong(0);
    private final AtomicLong filesFailed = new AtomicLong(0);
    private final AtomicLong bytesProcessed = new AtomicLong(0);

    // Reconciliation-specific counters
    private final AtomicLong orphansDeleted = new AtomicLong(0);
    private final AtomicLong filesSkippedUnchanged = new AtomicLong(0);
    private final AtomicLong reconciliationTimeMs = new AtomicLong(0);

    private final Map<String, DirectoryStats> directoryStats = new ConcurrentHashMap<>();

    // In-flight file tracking (file path -> start timestamp in millis)
    private final ConcurrentHashMap<String, Long> activeFiles = new ConcurrentHashMap<>();

    // Periodic progress notification timer
    private final ScheduledExecutorService progressTimerExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "progress-timer");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> progressTimerFuture;

    private volatile long startTime = 0;
    /** The crawl mode currently tracked: "full" or "incremental". */
    private volatile String crawlMode = "full";

    public CrawlStatisticsTracker(final ApplicationConfig config, final NotificationService notificationService) {
        this.config = config;
        this.notificationService = notificationService;
    }

    public void reset() {
        filesFound.set(0);
        filesProcessed.set(0);
        filesIndexed.set(0);
        filesFailed.set(0);
        bytesProcessed.set(0);
        orphansDeleted.set(0);
        filesSkippedUnchanged.set(0);
        reconciliationTimeMs.set(0);
        directoryStats.clear();
        activeFiles.clear();
        startTime = System.currentTimeMillis();
        crawlMode = "full";
    }

    public void incrementFilesFound(final String directory) {
        filesFound.incrementAndGet();
        getOrCreateDirectoryStats(directory).filesFound.incrementAndGet();
    }

    public void incrementFilesProcessed(final String directory, final long bytes) {
        filesProcessed.incrementAndGet();
        bytesProcessed.addAndGet(bytes);
        getOrCreateDirectoryStats(directory).filesProcessed.incrementAndGet();
    }

    public void incrementFilesIndexed(final String directory) {
        filesIndexed.incrementAndGet();
        getOrCreateDirectoryStats(directory).filesIndexed.incrementAndGet();
    }

    public void incrementFilesFailed(final String directory) {
        filesFailed.incrementAndGet();
        getOrCreateDirectoryStats(directory).filesFailed.incrementAndGet();
    }

    /** Record that the reconciliation phase deleted this many orphan documents. */
    public void setOrphansDeleted(final long count) {
        orphansDeleted.set(count);
    }

    /** Record that the reconciliation phase determined this many files are unchanged. */
    public void setFilesSkippedUnchanged(final long count) {
        filesSkippedUnchanged.set(count);
    }

    /** Record the wall-clock time spent in the reconciliation phase. */
    public void setReconciliationTimeMs(final long timeMs) {
        reconciliationTimeMs.set(timeMs);
    }

    /** Set the crawl mode for this run: "full" or "incremental". */
    public void setCrawlMode(final String mode) {
        this.crawlMode = mode;
    }

    /** Register a file as currently being processed (extraction/indexing in progress). */
    public void registerActiveFile(final String path) {
        activeFiles.put(path, System.currentTimeMillis());
    }

    /** Unregister a file after processing completes (success or failure). */
    public void unregisterActiveFile(final String path) {
        activeFiles.remove(path);
    }

    /** Returns a snapshot of all currently active files (path -> start timestamp). */
    public Map<String, Long> getActiveFiles() {
        return new ConcurrentHashMap<>(activeFiles);
    }

    /**
     * Start sending periodic progress notifications at the configured interval.
     * Replaces the old file-count-based trigger mechanism.
     */
    public void startPeriodicNotifications() {
        final long intervalMs = config.getProgressNotificationIntervalMs();
        progressTimerFuture = progressTimerExecutor.scheduleAtFixedRate(
                this::sendProgressNotification,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        logger.debug("Started periodic progress notifications every {}ms", intervalMs);
    }

    /**
     * Stop periodic progress notifications. Does not shut down the executor,
     * so notifications can be restarted for the next crawl.
     */
    public void stopPeriodicNotifications() {
        final ScheduledFuture<?> future = progressTimerFuture;
        if (future != null) {
            future.cancel(false);
            progressTimerFuture = null;
            logger.debug("Stopped periodic progress notifications");
        }
    }

    /**
     * Shut down the progress timer executor. Called on application shutdown.
     */
    public void shutdown() {
        stopPeriodicNotifications();
        progressTimerExecutor.shutdown();
        try {
            if (!progressTimerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                progressTimerExecutor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            progressTimerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private DirectoryStats getOrCreateDirectoryStats(final String directory) {
        return directoryStats.computeIfAbsent(directory, k -> new DirectoryStats());
    }

    private void sendProgressNotification() {
        try {
            final CrawlStatistics stats = getStatistics();
            final StringBuilder message = new StringBuilder();
            message.append(String.format(
                    "Indexed %d/%d files (%.1f files/sec, %.2f MB/sec)",
                    stats.filesIndexed(),
                    stats.filesFound(),
                    stats.filesPerSecond(),
                    stats.megabytesPerSecond()
            ));

            // Append currently processing filenames if any are active
            final List<CrawlStatistics.ActiveFile> processing = stats.currentlyProcessing();
            if (!processing.isEmpty()) {
                final String fileNames = processing.stream()
                        .map(af -> Paths.get(af.filePath()).getFileName().toString())
                        .collect(Collectors.joining(", "));
                message.append(" \u2014 processing: ").append(fileNames);
            }

            final String msg = message.toString();
            notificationService.notify("Crawler Progress", msg);
            logger.info("Crawler progress: {}", msg);
        } catch (final Exception e) {
            // Must catch all exceptions: ScheduledExecutorService silently cancels
            // the periodic task if the Runnable throws any uncaught exception.
            logger.error("Failed to send progress notification", e);
        }
    }

    public void sendStartNotification(final int directoryCount) {
        final String message = String.format("Indexing %d directories", directoryCount);
        notificationService.notify("Crawler Started", message);
        logger.info("Crawler started: {}", message);
    }

    public void sendCompleteNotification() {
        final CrawlStatistics stats = getStatistics();
        final String message = String.format(
                "Indexed %d documents in %.1f seconds (%.1f files/sec)",
                stats.filesIndexed(),
                stats.elapsedTimeMs() / 1000.0,
                stats.filesPerSecond()
        );
        notificationService.notify("Crawl Complete", message);
        logger.info("Crawl complete: {}", message);
    }

    public void sendPauseNotification() {
        notificationService.notify("Crawler Paused", "Crawling has been paused");
        logger.info("Crawler paused");
    }

    public void sendResumeNotification() {
        notificationService.notify("Crawler Resumed", "Crawling has been resumed");
        logger.info("Crawler resumed");
    }

    /**
     * Notify that the reconciliation phase has started.
     */
    public void sendReconciliationStartNotification() {
        notificationService.notify("Reconciliation", "Comparing index with filesystem...");
        logger.info("Reconciliation phase started");
    }

    /**
     * Notify that the reconciliation phase has completed and log its outcome.
     *
     * @param result the reconciliation result containing diff sets
     */
    public void sendReconciliationCompleteNotification(final ReconciliationResult result) {
        final String message = String.format(
                "Reconciliation done in %dms: %d to add, %d to update, %d to delete, %d unchanged",
                result.reconciliationTimeMs(),
                result.filesToAdd().size(),
                result.filesToUpdate().size(),
                result.filesToDelete().size(),
                result.unchangedCount()
        );
        notificationService.notify("Reconciliation Complete", message);
        logger.info("Reconciliation complete: {}", message);
    }

    public CrawlStatistics getStatistics() {
        final Map<String, CrawlStatistics.DirectoryStatistics> perDirStats = new ConcurrentHashMap<>();
        for (final Map.Entry<String, DirectoryStats> entry : directoryStats.entrySet()) {
            final DirectoryStats stats = entry.getValue();
            perDirStats.put(entry.getKey(), new CrawlStatistics.DirectoryStatistics(
                    entry.getKey(),
                    stats.filesFound.get(),
                    stats.filesProcessed.get(),
                    stats.filesIndexed.get(),
                    stats.filesFailed.get()
            ));
        }

        // Snapshot active files and compute processing durations
        final long now = System.currentTimeMillis();
        final List<CrawlStatistics.ActiveFile> currentlyProcessing = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : activeFiles.entrySet()) {
            final long durationMs = now - entry.getValue();
            currentlyProcessing.add(new CrawlStatistics.ActiveFile(entry.getKey(), durationMs));
        }

        return new CrawlStatistics(
                filesFound.get(),
                filesProcessed.get(),
                filesIndexed.get(),
                filesFailed.get(),
                bytesProcessed.get(),
                startTime,
                System.currentTimeMillis(),
                perDirStats,
                orphansDeleted.get(),
                filesSkippedUnchanged.get(),
                reconciliationTimeMs.get(),
                crawlMode,
                currentlyProcessing
        );
    }

    private static class DirectoryStats {
        final AtomicLong filesFound = new AtomicLong(0);
        final AtomicLong filesProcessed = new AtomicLong(0);
        final AtomicLong filesIndexed = new AtomicLong(0);
        final AtomicLong filesFailed = new AtomicLong(0);
    }
}
