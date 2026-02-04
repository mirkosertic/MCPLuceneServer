package de.mirkosertic.mcp.luceneserver.crawler;

import java.util.Map;

public record CrawlStatistics(
        long filesFound,
        long filesProcessed,
        long filesIndexed,
        long filesFailed,
        long bytesProcessed,
        long startTimeMs,
        long endTimeMs,
        Map<String, DirectoryStatistics> perDirectoryStats,
        /** Number of orphan documents removed from the index during reconciliation. */
        long orphansDeleted,
        /** Number of files skipped because they were unchanged since the last crawl. */
        long filesSkippedUnchanged,
        /** Wall-clock time in milliseconds spent in the reconciliation phase. */
        long reconciliationTimeMs,
        /** The crawl mode that produced these statistics: {@code "full"} or {@code "incremental"}. */
        String crawlMode
) {
    public double filesPerSecond() {
        final long elapsedMs = endTimeMs - startTimeMs;
        if (elapsedMs == 0) return 0;
        return (double) filesProcessed / (elapsedMs / 1000.0);
    }

    public double megabytesPerSecond() {
        final long elapsedMs = endTimeMs - startTimeMs;
        if (elapsedMs == 0) return 0;
        final double megabytes = bytesProcessed / (1024.0 * 1024.0);
        return megabytes / (elapsedMs / 1000.0);
    }

    public long elapsedTimeMs() {
        return endTimeMs - startTimeMs;
    }

    public record DirectoryStatistics(
            String directory,
            long filesFound,
            long filesProcessed,
            long filesIndexed,
            long filesFailed
    ) {
    }
}
