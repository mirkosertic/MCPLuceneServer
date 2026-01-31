package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for the startCrawl tool.
 */
public record StartCrawlRequest(
        @Nullable
        @Description("If true, clears the index before crawling. Default is false.")
        Boolean fullReindex
) {
    /**
     * Create from a Map of arguments.
     */
    public static StartCrawlRequest fromMap(final java.util.Map<String, Object> args) {
        return new StartCrawlRequest((Boolean) args.get("fullReindex"));
    }

    /**
     * Get the effective fullReindex value with default.
     */
    public boolean effectiveFullReindex() {
        return fullReindex != null && fullReindex;
    }
}
