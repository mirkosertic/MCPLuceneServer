package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the startCrawl tool.
 */
public record StartCrawlResponse(
        boolean success,
        String message,
        boolean fullReindex,
        String error
) {
    public static StartCrawlResponse success(final boolean fullReindex) {
        return new StartCrawlResponse(true, "Crawl started", fullReindex, null);
    }

    public static StartCrawlResponse error(final String errorMessage) {
        return new StartCrawlResponse(false, null, false, errorMessage);
    }
}
