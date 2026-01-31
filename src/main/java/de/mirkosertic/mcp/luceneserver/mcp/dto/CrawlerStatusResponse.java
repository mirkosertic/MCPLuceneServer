package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the getCrawlerStatus tool.
 */
public record CrawlerStatusResponse(
        boolean success,
        String state,
        String error
) {
    public static CrawlerStatusResponse success(final String state) {
        return new CrawlerStatusResponse(true, state, null);
    }

    public static CrawlerStatusResponse error(final String errorMessage) {
        return new CrawlerStatusResponse(false, null, errorMessage);
    }
}
