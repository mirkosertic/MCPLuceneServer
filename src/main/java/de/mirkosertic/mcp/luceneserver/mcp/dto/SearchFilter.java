package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record SearchFilter(
        @Description("The field name to filter on (e.g. language, file_extension, file_type, author, creator, subject, file_size, created_date, modified_date, indexed_date)")
        String field,

        @Nullable
        @Description("Filter operator: eq (default), in, not, not_in, range")
        String operator,

        @Nullable
        @Description("Value for eq/not operators")
        String value,

        @Nullable
        @Description("Values for in/not_in operators")
        List<String> values,

        @Nullable
        @Description("Range start (inclusive). For date fields use ISO-8601: '2024-01-01', '2024-01-15T10:30:00', or '2024-06-15T14:30:00Z'")
        String from,

        @Nullable
        @Description("Range end (inclusive). For date fields use ISO-8601 format.")
        String to,

        @Nullable
        @Description("Client-provided timestamp for tracking when this filter was added. Round-tripped in activeFilters response.")
        String addedAt
) {
    public String effectiveOperator() {
        return operator != null ? operator : "eq";
    }

    @SuppressWarnings("unchecked")
    public static SearchFilter fromMap(final Map<String, Object> map) {
        final String field = (String) map.get("field");
        final String operator = (String) map.get("operator");
        final String value = (String) map.get("value");

        final List<String> values;
        if (map.get("values") instanceof List<?> rawList) {
            values = rawList.stream()
                    .map(Object::toString)
                    .toList();
        } else {
            values = null;
        }

        final String from = (String) map.get("from");
        final String to = (String) map.get("to");
        final String addedAt = (String) map.get("addedAt");

        return new SearchFilter(field, operator, value, values, from, to, addedAt);
    }
}
