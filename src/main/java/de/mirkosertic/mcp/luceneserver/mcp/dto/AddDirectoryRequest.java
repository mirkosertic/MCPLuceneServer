package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Request DTO for the addCrawlableDirectory tool.
 */
public record AddDirectoryRequest(
        @Description("Absolute path to the directory to crawl")
        String path,

        @Nullable
        @Description("If true, immediately starts crawling the new directory. Default is false.")
        Boolean crawlNow
) {
    public static AddDirectoryRequest fromMap(final Map<String, Object> args) {
        return new AddDirectoryRequest(
                (String) args.get("path"),
                (Boolean) args.get("crawlNow")
        );
    }

    public boolean effectiveCrawlNow() {
        return crawlNow != null && crawlNow;
    }
}
