package de.mirkosertic.mcp.luceneserver.mcp.dto;

/**
 * Information about query rewrites performed by Lucene.
 */
public record QueryRewrite(
        String original,
        String rewritten,
        String reason
) {
}
