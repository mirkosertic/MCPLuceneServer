package de.mirkosertic.mcp.luceneserver.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules periodic metadata synchronization from the database to the Lucene index.
 * <p>
 * Runs a single background (daemon) thread that executes
 * {@link MetadataSyncService#syncMetadata()} at a configurable interval
 * (default: 5 minutes, first execution delayed by one full interval).
 *
 * <p>Concurrency warning: the sync service is NOT thread-safe with ongoing crawls.
 * If a crawl is running while sync executes, race conditions may occur (same file
 * indexed twice, which is harmless but wasteful).  A future improvement would be
 * to check crawler state before executing the sync.</p>
 */
public class MetadataSyncScheduler implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MetadataSyncScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final MetadataSyncService syncService;
    private final JdbcMetadataConfig config;
    private ScheduledFuture<?> scheduledTask;

    public MetadataSyncScheduler(
            final MetadataSyncService syncService,
            final JdbcMetadataConfig config) {
        this.syncService = syncService;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "metadata-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the sync scheduler.
     * If sync is disabled in the config, or the interval is invalid, this is a no-op.
     */
    public void start() {
        if (!config.sync().enabled()) {
            logger.info("Metadata sync scheduler disabled (sync.enabled=false)");
            return;
        }

        final long intervalMinutes = config.sync().intervalMinutes();
        if (intervalMinutes <= 0) {
            logger.warn("Invalid sync interval: {} minutes — scheduler not started", intervalMinutes);
            return;
        }

        scheduledTask = scheduler.scheduleAtFixedRate(
                this::runSync,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES);

        logger.info("Metadata sync scheduler started (interval: {} minutes)", intervalMinutes);
    }

    private void runSync() {
        try {
            logger.debug("Starting scheduled metadata sync");
            final MetadataSyncService.SyncResult result = syncService.syncMetadata();
            if (result.totalChanges() > 0) {
                logger.info("Metadata sync completed: {} changes, {} re-indexed, {} deleted, {} errors",
                        result.totalChanges(), result.reindexed(), result.deleted(), result.errors());
            } else {
                logger.debug("Metadata sync completed: no changes detected");
            }
        } catch (final Exception e) {
            // Must not let exceptions kill the scheduler thread
            logger.error("Scheduled metadata sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Stop the scheduler and wait for any in-progress sync to finish (up to 10 seconds).
     */
    @Override
    public void close() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
            logger.debug("Metadata sync scheduler cancelled");
        }

        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Metadata sync did not finish within 10 s — forcing shutdown");
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Metadata sync scheduler did not terminate");
                }
            } else {
                logger.info("Metadata sync scheduler stopped gracefully");
            }
        } catch (final InterruptedException e) {
            logger.warn("Interrupted while waiting for metadata sync to complete");
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
