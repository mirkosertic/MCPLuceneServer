package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the purgeIndex tool.
 * Returns immediately with an operationId that can be used to poll for status.
 */
public record PurgeIndexResponse(
        boolean success,
        String operationId,
        Long documentsDeleted,
        Boolean fullPurge,
        String message,
        String error
) {
    public static PurgeIndexResponse started(final String operationId, final long documentsDeleted, final boolean fullPurge) {
        final String fullPurgeNote = fullPurge
                ? " Index files will be deleted and the index will be reinitialized."
                : " Documents marked for deletion; disk space reclaimed on next merge.";
        return new PurgeIndexResponse(true, operationId, documentsDeleted, fullPurge,
                "Purge started." + fullPurgeNote + " Use getIndexAdminStatus to poll for progress.", null);
    }

    public static PurgeIndexResponse error(final String errorMessage) {
        return new PurgeIndexResponse(false, null, null, null, null, errorMessage);
    }

    public static PurgeIndexResponse notConfirmed() {
        return new PurgeIndexResponse(false, null, null, null, null,
                "Operation not confirmed. Set confirm=true to proceed. " +
                "WARNING: This will delete all documents from the index.");
    }

    public static PurgeIndexResponse alreadyRunning(final String currentOperationId) {
        return new PurgeIndexResponse(false, currentOperationId, null, null, null,
                "Another admin operation is already running. Use getIndexAdminStatus to check progress.");
    }
}
