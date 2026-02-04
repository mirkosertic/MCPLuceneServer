package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.LuceneIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Computes the diff between the current Lucene index and the filesystem,
 * then applies orphan deletions.  The result tells the crawler exactly
 * which files need to be (re-)indexed and which can be skipped.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Query the index for all {@code (file_path, modified_date)} pairs.</li>
 *   <li>Walk the configured directories, collecting {@code (file_path, mtime)} pairs
 *       for files that match the include/exclude patterns.</li>
 *   <li>Compute the four-way diff: DELETE, ADD, UPDATE, SKIP.</li>
 *   <li>Execute the bulk deletion of orphans via {@link LuceneIndexService}.</li>
 * </ol>
 * <p>
 * Reconciliation failures are logged but do <em>not</em> propagate -- the caller
 * is expected to fall back to a full crawl.
 */
public class IndexReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(IndexReconciliationService.class);

    private final LuceneIndexService indexService;

    public IndexReconciliationService(final LuceneIndexService indexService) {
        this.indexService = indexService;
    }

    /**
     * Run reconciliation against the given directories with the supplied pattern filters.
     * <p>
     * This method is intentionally synchronous and expected to run on a crawler thread.
     * It is designed to be fast: it only walks the filesystem and does one bulk Lucene
     * read -- no content extraction or heavy parsing is performed.
     *
     * @param directoriesToCrawl the root directories that will be crawled
     * @param includePatterns    glob patterns for files to include
     * @param excludePatterns    glob patterns for files to exclude
     * @return the full reconciliation result including timing; never null
     * @throws IOException if the index cannot be read or orphan deletions fail
     */
    public ReconciliationResult reconcile(final List<String> directoriesToCrawl,
                                          final List<String> includePatterns,
                                          final List<String> excludePatterns) throws IOException {

        final long startTime = System.currentTimeMillis();

        // Step 1: Snapshot the index -- map of file_path -> modified_date (epoch millis)
        final Map<String, Long> indexedDocuments = indexService.getAllIndexedDocuments();
        logger.info("Index snapshot: {} documents", indexedDocuments.size());

        // Step 2: Walk the filesystem and collect current (file_path, mtime) pairs
        final Map<String, Long> filesOnDisk = collectFilesOnDisk(directoriesToCrawl, includePatterns, excludePatterns);
        logger.info("Filesystem snapshot: {} files", filesOnDisk.size());

        // Step 3: Compute the four-way diff
        final Set<String> filesToDelete = new HashSet<>();
        final Set<String> filesToAdd = new HashSet<>();
        final Set<String> filesToUpdate = new HashSet<>();
        int unchangedCount = 0;

        // Paths in the index but NOT on disk => orphans to delete
        for (final String indexedPath : indexedDocuments.keySet()) {
            if (!filesOnDisk.containsKey(indexedPath)) {
                filesToDelete.add(indexedPath);
            }
        }

        // Paths on disk: compare against index
        for (final Map.Entry<String, Long> diskEntry : filesOnDisk.entrySet()) {
            final String filePath = diskEntry.getKey();
            final long diskMtime = diskEntry.getValue();

            final Long indexedMtime = indexedDocuments.get(filePath);
            if (indexedMtime == null) {
                // Not in the index at all -- needs to be added
                filesToAdd.add(filePath);
            } else if (diskMtime > indexedMtime) {
                // Disk is newer -- needs re-indexing
                filesToUpdate.add(filePath);
            } else {
                // Unchanged (disk mtime <= indexed mtime)
                unchangedCount++;
            }
        }

        final long reconciliationTimeMs = System.currentTimeMillis() - startTime;

        logger.info("Reconciliation diff computed in {}ms: delete={}, add={}, update={}, unchanged={}",
                reconciliationTimeMs, filesToDelete.size(), filesToAdd.size(), filesToUpdate.size(), unchangedCount);

        return new ReconciliationResult(filesToDelete, filesToAdd, filesToUpdate, unchangedCount, reconciliationTimeMs);
    }

    /**
     * Delete the orphan documents identified by the reconciliation result.
     * Safe to call with an empty set (no-op).
     *
     * @param filePaths the set of file paths to remove from the index
     * @throws IOException if the bulk delete fails
     */
    public void applyDeletions(final Set<String> filePaths) throws IOException {
        if (filePaths == null || filePaths.isEmpty()) {
            logger.debug("No orphan deletions to apply");
            return;
        }

        logger.info("Applying {} orphan deletions", filePaths.size());
        indexService.bulkDeleteByFilePaths(filePaths);
    }

    /**
     * Walk all configured directories and collect every file that matches the
     * include/exclude patterns as a {@code (absolutePath, lastModifiedTimeMillis)} pair.
     */
    private Map<String, Long> collectFilesOnDisk(final List<String> directories,
                                                  final List<String> includePatterns,
                                                  final List<String> excludePatterns) {

        final FilePatternMatcher matcher = new FilePatternMatcher(includePatterns, excludePatterns);
        final Map<String, Long> result = new HashMap<>();

        for (final String directory : directories) {
            final Path dirPath = Paths.get(directory);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                logger.warn("Reconciliation: skipping non-existent or non-directory path: {}", directory);
                continue;
            }

            try (final Stream<Path> paths = Files.walk(dirPath)) {
                paths.filter(Files::isRegularFile)
                     .filter(matcher::shouldInclude)
                     .forEach(file -> {
                         try {
                             final long mtime = Files.getLastModifiedTime(file).toMillis();
                             result.put(file.toString(), mtime);
                         } catch (final IOException e) {
                             logger.warn("Reconciliation: cannot read mtime for {}, skipping", file, e);
                         }
                     });
            } catch (final IOException e) {
                logger.error("Reconciliation: error walking directory {}", directory, e);
            }
        }

        return result;
    }
}
