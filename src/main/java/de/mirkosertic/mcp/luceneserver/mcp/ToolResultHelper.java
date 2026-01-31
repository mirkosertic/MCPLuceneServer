package de.mirkosertic.mcp.luceneserver.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Helper class for creating MCP tool results from DTOs.
 */
public final class ToolResultHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ToolResultHelper() {
    }

    /**
     * Create a CallToolResult from a response DTO.
     * The DTO is serialized to JSON and wrapped in a TextContent.
     *
     * @param response the response DTO (any record or object)
     * @return a CallToolResult containing the JSON-serialized response
     */
    public static McpSchema.CallToolResult createResult(final Object response) {
        final String json = toJson(response);
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isErrorResponse(response))
                .build();
    }

    /**
     * Create an error CallToolResult with a simple error message.
     *
     * @param errorMessage the error message
     * @return a CallToolResult indicating an error
     */
    public static McpSchema.CallToolResult createErrorResult(final String errorMessage) {
        final String json = "{\"success\":false,\"error\":\"" + escapeJson(errorMessage) + "\"}";
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(true)
                .build();
    }

    /**
     * Serialize an object to JSON.
     */
    public static String toJson(final Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (final JsonProcessingException e) {
            // Fallback to simple error JSON
            return "{\"success\":false,\"error\":\"JSON serialization error: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Check if a response indicates an error (has success=false).
     */
    private static boolean isErrorResponse(final Object response) {
        if (response instanceof Record record) {
            try {
                final var successField = record.getClass().getRecordComponents();
                for (final var component : successField) {
                    if ("success".equals(component.getName())) {
                        final Object value = component.getAccessor().invoke(record);
                        if (value instanceof Boolean success) {
                            return !success;
                        }
                    }
                }
            } catch (final Exception ignored) {
                // If we can't determine, assume not an error
            }
        }
        return false;
    }

    private static String escapeJson(final String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
