package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Response DTO for the listCrawlableDirectories tool.
 */
public record ListDirectoriesResponse(
        boolean success,
        List<String> directories,
        int totalDirectories,
        String configPath,
        boolean environmentOverride,
        String error
) {
    public static ListDirectoriesResponse success(
            final List<String> directories,
            final String configPath,
            final boolean environmentOverride) {
        return new ListDirectoriesResponse(
                true, directories, directories.size(), configPath, environmentOverride, null
        );
    }

    public static ListDirectoriesResponse error(final String errorMessage) {
        return new ListDirectoriesResponse(false, null, 0, null, false, errorMessage);
    }
}
