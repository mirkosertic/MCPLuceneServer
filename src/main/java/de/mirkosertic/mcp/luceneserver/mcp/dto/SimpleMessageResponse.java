package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Simple response DTO for tools that return just a success/message.
 * Used for pauseCrawler, resumeCrawler, etc.
 */
public record SimpleMessageResponse(
        boolean success,
        String message,
        String error
) {
    public static SimpleMessageResponse success(final String message) {
        return new SimpleMessageResponse(true, message, null);
    }

    public static SimpleMessageResponse error(final String errorMessage) {
        return new SimpleMessageResponse(false, null, errorMessage);
    }
}
