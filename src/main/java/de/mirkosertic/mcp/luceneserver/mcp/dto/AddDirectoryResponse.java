package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Response DTO for the addCrawlableDirectory tool.
 */
public record AddDirectoryResponse(
        boolean success,
        String message,
        int totalDirectories,
        List<String> directories,
        boolean crawlStarted,
        String error
) {
    public static AddDirectoryResponse success(
            final String message,
            final List<String> directories,
            final boolean crawlStarted) {
        return new AddDirectoryResponse(
                true, message, directories.size(), directories, crawlStarted, null
        );
    }

    public static AddDirectoryResponse error(final String errorMessage) {
        return new AddDirectoryResponse(false, null, 0, null, false, errorMessage);
    }
}
