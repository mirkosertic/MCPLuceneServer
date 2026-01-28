package de.mirkosertic.mcp.luceneserver.crawler;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CrawlExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(CrawlExecutorService.class);

    private final ThreadPoolExecutor executor;

    public CrawlExecutorService(final CrawlerProperties properties) {
        final AtomicInteger threadCounter = new AtomicInteger(0);
        final ThreadFactory threadFactory = r -> {
            final Thread thread = new Thread(r, "crawler-" + threadCounter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };

        this.executor = new ThreadPoolExecutor(
                properties.getThreadPoolSize(),
                properties.getThreadPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10000),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        logger.info("CrawlExecutorService initialized with {} threads", properties.getThreadPoolSize());
    }

    public Future<?> submit(final Runnable task) {
        return executor.submit(task);
    }

    public <T> Future<T> submit(final Callable<T> task) {
        return executor.submit(task);
    }

    public void execute(final Runnable task) {
        executor.execute(task);
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    public void awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        executor.awaitTermination(timeout, unit);
    }

    @PreDestroy
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
