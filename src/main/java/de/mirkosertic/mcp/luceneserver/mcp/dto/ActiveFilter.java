package de.mirkosertic.mcp.luceneserver.mcp.dto;

import java.util.List;

public record ActiveFilter(
        String field,
        String operator,
        String value,
        List<String> values,
        String from,
        String to,
        String addedAt,
        long matchCount
) {
    public static ActiveFilter fromFilter(final SearchFilter filter, final long matchCount) {
        return new ActiveFilter(
                filter.field(),
                filter.operator(),
                filter.value(),
                filter.values(),
                filter.from(),
                filter.to(),
                filter.addedAt(),
                matchCount
        );
    }
}
