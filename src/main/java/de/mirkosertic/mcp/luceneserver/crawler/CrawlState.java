package de.mirkosertic.mcp.luceneserver.crawler;

/**
 * Persisted state of the last completed crawl.
 * <p>
 * Saved to {@code ~/.mcplucene/crawl-state.yaml} after every successful crawl
 * so that the next startup can determine whether a full or incremental crawl
 * is needed.
 */
public record CrawlState(
        /** Epoch-millis timestamp when the last crawl completed successfully. */
        long lastCompletionTimeMs,
        /** Number of documents in the index at the end of the last crawl. */
        long lastDocumentCount,
        /** The mode of the last crawl: {@code "full"} or {@code "incremental"}. */
        String lastCrawlMode
) {
}
