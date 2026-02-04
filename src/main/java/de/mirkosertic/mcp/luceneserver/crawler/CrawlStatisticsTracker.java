package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.NotificationService;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    private volatile long startTime = 0;
    private volatile long lastNotificationTime = 0;
    private volatile long lastNotifiedFileCount = 0;
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
        startTime = System.currentTimeMillis();
        lastNotificationTime = startTime;
        lastNotifiedFileCount = 0;
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
        checkNotificationTrigger();
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

    private DirectoryStats getOrCreateDirectoryStats(final String directory) {
        return directoryStats.computeIfAbsent(directory, k -> new DirectoryStats());
    }

    private void checkNotificationTrigger() {
        final long filesProcessedNow = filesProcessed.get();
        final long timeSinceLastNotification = System.currentTimeMillis() - lastNotificationTime;

        if (filesProcessedNow - lastNotifiedFileCount >= config.getProgressNotificationFiles() ||
            timeSinceLastNotification >= config.getProgressNotificationIntervalMs()) {
            sendProgressNotification();
            lastNotificationTime = System.currentTimeMillis();
            lastNotifiedFileCount = filesProcessedNow;
        }
    }

    private void sendProgressNotification() {
        final CrawlStatistics stats = getStatistics();
        final String message = String.format(
                "Indexed %d/%d files (%.1f files/sec, %.2f MB/sec)",
                stats.filesIndexed(),
                stats.filesFound(),
                stats.filesPerSecond(),
                stats.megabytesPerSecond()
        );
        notificationService.notify("Crawler Progress", message);
        logger.info("Crawler progress: {}", message);
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
                crawlMode
        );
    }

    private static class DirectoryStats {
        final AtomicLong filesFound = new AtomicLong(0);
        final AtomicLong filesProcessed = new AtomicLong(0);
        final AtomicLong filesIndexed = new AtomicLong(0);
        final AtomicLong filesFailed = new AtomicLong(0);
    }
}
