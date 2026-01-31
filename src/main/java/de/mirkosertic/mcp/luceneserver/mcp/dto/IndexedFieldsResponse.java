package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.Set;

/**
 * Response DTO for the listIndexedFields tool.
 */
public record IndexedFieldsResponse(
        boolean success,
        Set<String> fields,
        String error
) {
    public static IndexedFieldsResponse success(final Set<String> fields) {
        return new IndexedFieldsResponse(true, fields, null);
    }

    public static IndexedFieldsResponse error(final String errorMessage) {
        return new IndexedFieldsResponse(false, null, errorMessage);
    }
}
