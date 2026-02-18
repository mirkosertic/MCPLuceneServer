package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.crawler.CrawlState;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the getCrawlerStats tool.
 */
public record CrawlerStatsResponse(
        boolean success,
        long filesFound,
        long filesProcessed,
        long filesIndexed,
        long filesFailed,
        long bytesProcessed,
        double filesPerSecond,
        double megabytesPerSecond,
        long elapsedTimeMs,
        Map<String, CrawlStatistics.DirectoryStatistics> perDirectoryStats,
        long orphansDeleted,
        long filesSkippedUnchanged,
        long reconciliationTimeMs,
        String crawlMode,
        List<CrawlStatistics.ActiveFile> currentlyProcessing,
        @Nullable Long lastCrawlCompletionTimeMs,
        @Nullable Long lastCrawlDocumentCount,
        @Nullable String lastCrawlMode,
        String error
) {
    public static CrawlerStatsResponse success(final CrawlStatistics stats, @Nullable final CrawlState lastCrawlState) {
        return new CrawlerStatsResponse(
                true,
                stats.filesFound(),
                stats.filesProcessed(),
                stats.filesIndexed(),
                stats.filesFailed(),
                stats.bytesProcessed(),
                stats.filesPerSecond(),
                stats.megabytesPerSecond(),
                stats.elapsedTimeMs(),
                stats.perDirectoryStats(),
                stats.orphansDeleted(),
                stats.filesSkippedUnchanged(),
                stats.reconciliationTimeMs(),
                stats.crawlMode(),
                stats.currentlyProcessing(),
                lastCrawlState != null ? lastCrawlState.lastCompletionTimeMs() : null,
                lastCrawlState != null ? lastCrawlState.lastDocumentCount() : null,
                lastCrawlState != null ? lastCrawlState.lastCrawlMode() : null,
                null
        );
    }

    public static CrawlerStatsResponse error(final String errorMessage) {
        return new CrawlerStatsResponse(false, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, null, List.of(), null, null, null, errorMessage);
    }
}
