package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Response DTO for the unlockIndex tool.
 */
public record UnlockIndexResponse(
        boolean success,
        String message,
        Boolean lockFileExisted,
        String lockFilePath,
        String error
) {
    public static UnlockIndexResponse success(final String message, final boolean lockFileExisted, final String lockFilePath) {
        return new UnlockIndexResponse(true, message, lockFileExisted, lockFilePath, null);
    }

    public static UnlockIndexResponse error(final String errorMessage) {
        return new UnlockIndexResponse(false, null, null, null, errorMessage);
    }

    public static UnlockIndexResponse notConfirmed() {
        return new UnlockIndexResponse(false, null, null, null,
                "Operation not confirmed. Set confirm=true to proceed. " +
                "WARNING: Only use this if you are certain no other process is using the index.");
    }
}
