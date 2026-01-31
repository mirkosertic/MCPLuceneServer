package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages thread pool for crawler operations.
 * Provides configurable parallelism for directory crawling.
 */
public class CrawlExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(CrawlExecutorService.class);

    private final ThreadPoolExecutor executor;

    public CrawlExecutorService(final ApplicationConfig config) {
        final AtomicInteger threadCounter = new AtomicInteger(0);
        final ThreadFactory threadFactory = r -> {
            final Thread thread = new Thread(r, "crawler-" + threadCounter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };

        this.executor = new ThreadPoolExecutor(
                config.getThreadPoolSize(),
                config.getThreadPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10000),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        logger.info("CrawlExecutorService initialized with {} threads", config.getThreadPoolSize());
    }

    public Future<?> submit(final Runnable task) {
        return executor.submit(task);
    }

    public void execute(final Runnable task) {
        executor.execute(task);
    }

    /**
     * Shutdown the executor service. Should be called on application shutdown.
     */
    public void shutdown() {
        logger.info("Shutting down CrawlExecutorService");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("CrawlExecutorService did not terminate in time, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            logger.error("Interrupted while waiting for CrawlExecutorService to terminate", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
