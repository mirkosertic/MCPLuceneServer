package de.mirkosertic.mcp.luceneserver.crawler;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

@Service
public class DirectoryWatcherService {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryWatcherService.class);

    private final CrawlerProperties properties;
    private final Map<WatchKey, WatchInfo> watchKeys = new ConcurrentHashMap<>();
    private final ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "directory-watcher");
        thread.setDaemon(true);
        return thread;
    });

    private volatile WatchService watchService;

    public DirectoryWatcherService(final CrawlerProperties properties) {
        this.properties = properties;
    }

    public void watchDirectory(final Path directory, final FileChangeListener listener) throws IOException {
        if (watchService == null) {
            watchService = FileSystems.getDefault().newWatchService();
        }

        // Register directory and all subdirectories
        registerRecursive(directory, listener);

        // Start watch loop if not already running
        watchExecutor.execute(this::processEvents);
    }

    private void registerRecursive(final Path directory, final FileChangeListener listener) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                final WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                watchKeys.put(key, new WatchInfo(dir, listener));
                logger.debug("Registered watch for directory: {}", dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void processEvents() {
        logger.info("Directory watcher started");

        while (!Thread.currentThread().isInterrupted()) {
            final WatchKey key;
            try {
                // Wait for events with timeout
                key = watchService.poll(properties.getWatchPollIntervalMs(), TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (final ClosedWatchServiceException e) {
                logger.info("Watch service closed");
                break;
            }

            final WatchInfo watchInfo = watchKeys.get(key);
            if (watchInfo == null) {
                logger.warn("Watch key not recognized");
                key.reset();
                continue;
            }

            for (final WatchEvent<?> event : key.pollEvents()) {
                final WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    logger.warn("Watch event overflow");
                    continue;
                }

                @SuppressWarnings("unchecked") final WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                final Path filename = pathEvent.context();
                final Path fullPath = watchInfo.directory.resolve(filename);

                try {
                    if (kind == ENTRY_CREATE) {
                        // If directory created, register it for watching
                        if (Files.isDirectory(fullPath)) {
                            registerRecursive(fullPath, watchInfo.listener);
                        } else if (Files.isRegularFile(fullPath)) {
                            watchInfo.listener.onFileCreated(fullPath);
                        }
                    } else if (kind == ENTRY_MODIFY) {
                        if (Files.isRegularFile(fullPath)) {
                            watchInfo.listener.onFileModified(fullPath);
                        }
                    } else if (kind == ENTRY_DELETE) {
                        watchInfo.listener.onFileDeleted(fullPath);
                    }
                } catch (final Exception e) {
                    logger.error("Error processing watch event for: {}", fullPath, e);
                }
            }

            final boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
                logger.info("Watch key no longer valid, removed from tracking");
            }
        }

        logger.info("Directory watcher stopped");
    }

    public void stopAll() throws IOException {
        logger.info("Stopping all directory watchers");
        watchExecutor.shutdownNow();

        if (watchService != null) {
            watchService.close();
        }

        watchKeys.clear();
    }

    @PreDestroy
    public void shutdown() {
        try {
            stopAll();
        } catch (final IOException e) {
            logger.error("Error shutting down directory watcher service", e);
        }
    }

    private record WatchInfo(Path directory, FileChangeListener listener) {
    }
}
