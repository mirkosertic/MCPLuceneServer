package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.Map;

/**
 * Request DTO for the getDocumentDetails tool.
 */
public record GetDocumentDetailsRequest(
        @Description("Absolute path to the file (must match exactly the file_path stored in the index)")
        String filePath
) {
    public static GetDocumentDetailsRequest fromMap(final Map<String, Object> args) {
        return new GetDocumentDetailsRequest((String) args.get("filePath"));
    }
}
