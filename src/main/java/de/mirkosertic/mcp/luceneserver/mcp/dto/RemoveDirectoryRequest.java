package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.Map;

/**
 * Request DTO for the removeCrawlableDirectory tool.
 */
public record RemoveDirectoryRequest(
        @Description("Absolute path to the directory to remove")
        String path
) {
    public static RemoveDirectoryRequest fromMap(final Map<String, Object> args) {
        return new RemoveDirectoryRequest((String) args.get("path"));
    }
}
