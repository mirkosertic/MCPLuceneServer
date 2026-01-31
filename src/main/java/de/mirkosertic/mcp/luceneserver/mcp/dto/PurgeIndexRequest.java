package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Request DTO for the purgeIndex tool.
 * This is a destructive operation - requires explicit confirmation.
 */
public record PurgeIndexRequest(
        @Description("Must be set to true to confirm the purge operation. " +
                "WARNING: This will delete all documents from the index.")
        Boolean confirm,

        @Nullable
        @Description("If true, also deletes index files and reinitializes the index. " +
                "This reclaims disk space immediately. Default is false (deleteAll + commit only).")
        Boolean fullPurge
) {
    /**
     * Create from a Map of arguments.
     */
    public static PurgeIndexRequest fromMap(final Map<String, Object> args) {
        return new PurgeIndexRequest(
                (Boolean) args.get("confirm"),
                (Boolean) args.get("fullPurge")
        );
    }

    /**
     * Check if the operation is confirmed.
     */
    public boolean isConfirmed() {
        return confirm != null && confirm;
    }

    /**
     * Get the effective fullPurge value with default.
     */
    public boolean effectiveFullPurge() {
        return fullPurge != null && fullPurge;
    }
}
