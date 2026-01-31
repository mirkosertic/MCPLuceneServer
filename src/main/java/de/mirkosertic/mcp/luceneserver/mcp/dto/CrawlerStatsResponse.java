package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;

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
        String error
) {
    public static CrawlerStatsResponse success(final CrawlStatistics stats) {
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
                null
        );
    }

    public static CrawlerStatsResponse error(final String errorMessage) {
        return new CrawlerStatsResponse(false, 0, 0, 0, 0, 0, 0, 0, 0, null, errorMessage);
    }
}
