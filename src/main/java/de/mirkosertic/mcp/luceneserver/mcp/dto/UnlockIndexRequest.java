package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.Map;

/**
 * Request DTO for the unlockIndex tool.
 * This is a dangerous recovery operation - requires explicit confirmation.
 */
public record UnlockIndexRequest(
        @Description("Must be set to true to confirm the unlock operation. " +
                "WARNING: Only use this if you are certain no other process is using the index. " +
                "Unlocking an active index can cause data corruption.")
        Boolean confirm
) {
    /**
     * Create from a Map of arguments.
     */
    public static UnlockIndexRequest fromMap(final Map<String, Object> args) {
        return new UnlockIndexRequest((Boolean) args.get("confirm"));
    }

    /**
     * Check if the operation is confirmed.
     */
    public boolean isConfirmed() {
        return confirm != null && confirm;
    }
}
