package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.Map;

/**
 * Response DTO for the getDocumentDetails tool.
 */
public record GetDocumentDetailsResponse(
        boolean success,
        Map<String, Object> document,
        String error
) {
    public static GetDocumentDetailsResponse success(final Map<String, Object> document) {
        return new GetDocumentDetailsResponse(true, document, null);
    }

    public static GetDocumentDetailsResponse error(final String errorMessage) {
        return new GetDocumentDetailsResponse(false, null, errorMessage);
    }
}
