package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Request DTO for the optimizeIndex tool.
 * Triggers a forceMerge operation which is long-running.
 */
public record OptimizeIndexRequest(
        @Nullable
        @Description("Target number of segments after optimization. Default is 1 for maximum optimization. " +
                "Higher values complete faster but provide less optimization.")
        Integer maxSegments
) {
    /**
     * Create from a Map of arguments.
     */
    public static OptimizeIndexRequest fromMap(final Map<String, Object> args) {
        return new OptimizeIndexRequest(
                args.get("maxSegments") != null ? ((Number) args.get("maxSegments")).intValue() : null
        );
    }

    /**
     * Get the effective maxSegments value with default.
     */
    public int effectiveMaxSegments() {
        return (maxSegments != null && maxSegments > 0) ? maxSegments : 1;
    }
}
