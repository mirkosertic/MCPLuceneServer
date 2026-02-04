package de.mirkosertic.mcp.luceneserver.crawler;

import java.util.Set;

/**
 * Immutable result of an index-vs-filesystem reconciliation pass.
 * <p>
 * Contains the complete diff between what is currently in the Lucene index
 * and what exists on disk, along with timing information.
 */
public record ReconciliationResult(
        /** File paths present in the index but no longer on disk -- candidates for deletion. */
        Set<String> filesToDelete,
        /** File paths present on disk but not yet in the index -- candidates for indexing. */
        Set<String> filesToAdd,
        /** File paths present in both, but with a newer mtime on disk -- candidates for re-indexing. */
        Set<String> filesToUpdate,
        /** Number of files that are identical on disk and in the index (skipped). */
        int unchangedCount,
        /** Wall-clock time in milliseconds spent performing the reconciliation. */
        long reconciliationTimeMs
) {
    /** Total number of files that need to be (re-)indexed after reconciliation. */
    public int filesToProcess() {
        return filesToAdd.size() + filesToUpdate.size();
    }
}
