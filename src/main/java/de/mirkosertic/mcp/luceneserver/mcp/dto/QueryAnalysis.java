package de.mirkosertic.mcp.luceneserver.mcp.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Analysis of the query structure and components.
 */
public record QueryAnalysis(
        String originalQuery,
        String parsedQueryType,
        List<QueryComponent> components,
        @Nullable List<QueryRewrite> rewrites,
        List<String> warnings
) {
}
