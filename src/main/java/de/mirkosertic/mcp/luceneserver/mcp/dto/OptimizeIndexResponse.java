package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the optimizeIndex tool.
 * Returns immediately with an operationId that can be used to poll for status.
 */
public record OptimizeIndexResponse(
        boolean success,
        String operationId,
        Integer targetSegments,
        Integer currentSegments,
        String message,
        String error
) {
    public static OptimizeIndexResponse started(final String operationId, final int targetSegments, final int currentSegments) {
        return new OptimizeIndexResponse(true, operationId, targetSegments, currentSegments,
                "Optimization started. Use getIndexAdminStatus to poll for progress.", null);
    }

    public static OptimizeIndexResponse error(final String errorMessage) {
        return new OptimizeIndexResponse(false, null, null, null, null, errorMessage);
    }

    public static OptimizeIndexResponse alreadyRunning(final String currentOperationId) {
        return new OptimizeIndexResponse(false, currentOperationId, null, null, null,
                "Another admin operation is already running. Use getIndexAdminStatus to check progress.");
    }

    public static OptimizeIndexResponse crawlerActive() {
        return new OptimizeIndexResponse(false, null, null, null, null,
                "Cannot optimize while crawler is active. Stop or pause the crawler first.");
    }
}
