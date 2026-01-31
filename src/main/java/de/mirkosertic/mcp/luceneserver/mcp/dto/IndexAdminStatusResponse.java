package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the getIndexAdminStatus tool.
 * Returns the current state of admin operations (optimize, purge).
 */
public record IndexAdminStatusResponse(
        boolean success,
        String state,
        String operationId,
        Integer progressPercent,
        String progressMessage,
        Long elapsedTimeMs,
        String lastOperationResult,
        String error
) {
    public static IndexAdminStatusResponse success(
            final String state,
            final String operationId,
            final Integer progressPercent,
            final String progressMessage,
            final Long elapsedTimeMs,
            final String lastOperationResult) {
        return new IndexAdminStatusResponse(true, state, operationId, progressPercent,
                progressMessage, elapsedTimeMs, lastOperationResult, null);
    }

    public static IndexAdminStatusResponse idle(final String lastOperationResult) {
        return new IndexAdminStatusResponse(true, "IDLE", null, null,
                "No admin operation running", null, lastOperationResult, null);
    }

    public static IndexAdminStatusResponse error(final String errorMessage) {
        return new IndexAdminStatusResponse(false, null, null, null, null, null, null, errorMessage);
    }
}
