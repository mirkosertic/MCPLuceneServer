package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

/**
 * Response DTO for the removeCrawlableDirectory tool.
 */
public record RemoveDirectoryResponse(
        boolean success,
        String message,
        int totalDirectories,
        List<String> directories,
        String error
) {
    public static RemoveDirectoryResponse success(
            final String message,
            final List<String> directories) {
        return new RemoveDirectoryResponse(
                true, message, directories.size(), directories, null
        );
    }

    public static RemoveDirectoryResponse error(final String errorMessage) {
        return new RemoveDirectoryResponse(false, null, 0, null, errorMessage);
    }

    public static RemoveDirectoryResponse notFound(final String path, final List<String> directories) {
        return new RemoveDirectoryResponse(
                false, null, directories.size(), directories,
                "Directory not found in configuration: " + path
        );
    }
}
