package de.mirkosertic.mcp.luceneserver.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses JSON metadata payloads returned by a JDBC query into typed field descriptors.
 * <p>
 * Expected JSON format:
 * <pre>
 * {
 *   "fields": [
 *     { "name": "customer_id", "type": "long",    "value":  42,          "faceted": true },
 *     { "name": "tags",        "type": "keyword",  "values": ["a","b"],  "faceted": true },
 *     { "name": "description", "type": "text",     "value":  "Some text"               },
 *     { "name": "doc_date",    "type": "date",     "value":  "2024-01-15T00:00:00Z"    }
 *   ]
 * }
 * </pre>
 */
public class JsonMetadataParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonMetadataParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

    public enum FieldType {
        KEYWORD,
        TEXT,
        INT,
        LONG,
        DATE
    }

    public record MetadataField(
            String name,
            FieldType type,
            List<Object> values,
            boolean faceted,
            boolean stored,
            boolean searchable
    ) {}

    /**
     * Parse a JSON string into a list of typed metadata fields.
     *
     * @param jsonString the JSON payload from the database column
     * @return list of parsed fields (may be empty if all fields are invalid)
     * @throws JsonProcessingException if the string is not valid JSON
     * @throws IllegalArgumentException if required JSON structure is missing
     */
    public List<MetadataField> parse(final String jsonString) throws JsonProcessingException {
        final JsonNode root = mapper.readTree(jsonString);

        if (!root.has("fields") || !root.get("fields").isArray()) {
            throw new IllegalArgumentException("JSON must have 'fields' array");
        }

        final List<MetadataField> result = new ArrayList<>();
        for (final JsonNode fieldNode : root.get("fields")) {
            final MetadataField field = parseField(fieldNode);
            if (field != null) {
                result.add(field);
            }
        }

        return Collections.unmodifiableList(result);
    }

    private MetadataField parseField(final JsonNode node) {
        if (!node.has("name") || !node.has("type")) {
            throw new IllegalArgumentException("Each field must have 'name' and 'type'");
        }

        final String name = node.get("name").asText();

        final FieldType type;
        try {
            type = FieldType.valueOf(node.get("type").asText().toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown field type '" + node.get("type").asText() + "' for field '" + name + "'");
        }

        if (node.has("value") && node.has("values")) {
            throw new IllegalArgumentException(
                    "Field '" + name + "' cannot have both 'value' and 'values'");
        }

        if (!node.has("value") && !node.has("values")) {
            throw new IllegalArgumentException(
                    "Field '" + name + "' must have either 'value' or 'values'");
        }

        final List<Object> values = new ArrayList<>();

        if (node.has("value")) {
            try {
                values.add(parseValue(node.get("value"), type, name));
            } catch (final IllegalArgumentException e) {
                logger.warn("Skipping field '{}' due to invalid value: {}", name, e.getMessage());
                return null;
            }
        } else {
            final JsonNode valuesNode = node.get("values");
            if (!valuesNode.isArray()) {
                throw new IllegalArgumentException("'values' in field '" + name + "' must be an array");
            }
            for (final JsonNode valueNode : valuesNode) {
                try {
                    values.add(parseValue(valueNode, type, name));
                } catch (final IllegalArgumentException e) {
                    logger.debug("Skipping invalid value in '{}' array: {}", name, e.getMessage());
                }
            }
        }

        final boolean faceted = node.has("faceted") && node.get("faceted").asBoolean();
        final boolean stored = !node.has("stored") || node.get("stored").asBoolean();
        final boolean searchable = !node.has("searchable") || node.get("searchable").asBoolean();

        return new MetadataField(name, type, Collections.unmodifiableList(values), faceted, stored, searchable);
    }

    private Object parseValue(final JsonNode node, final FieldType type, final String fieldName) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Value for field '" + fieldName + "' is null");
        }

        return switch (type) {
            case INT -> {
                if (!node.isNumber()) {
                    throw new IllegalArgumentException(
                            "INT value must be numeric, got: " + node.getNodeType());
                }
                final long lv = node.asLong();
                if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("INT value out of range: " + lv);
                }
                yield (int) lv;
            }
            case LONG -> {
                if (!node.isNumber()) {
                    throw new IllegalArgumentException(
                            "LONG field '" + fieldName + "' expects a numeric value, got: " + node.getNodeType());
                }
                yield node.asLong();
            }
            case DATE -> {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException(
                            "DATE field '" + fieldName + "' expects an ISO-8601 string, got: " + node.getNodeType());
                }
                try {
                    yield Instant.parse(node.asText()).toEpochMilli();
                } catch (final DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "Invalid ISO-8601 date for field '" + fieldName + "': " + node.asText(), e);
                }
            }
            case KEYWORD, TEXT -> node.asText();
        };
    }
}
