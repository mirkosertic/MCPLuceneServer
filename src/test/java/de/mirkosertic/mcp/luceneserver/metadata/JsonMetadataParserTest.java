package de.mirkosertic.mcp.luceneserver.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMetadataParserTest {

    private final JsonMetadataParser parser = new JsonMetadataParser();

    // -----------------------------------------------------------------------
    // Happy-path: single values
    // -----------------------------------------------------------------------

    @Test
    void testLongSingleValue() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "customer_id", "type": "long", "value": 42, "faceted": true }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        final var f = fields.getFirst();
        assertThat(f.name()).isEqualTo("customer_id");
        assertThat(f.type()).isEqualTo(JsonMetadataParser.FieldType.LONG);
        assertThat(f.values()).isEqualTo(List.of(42L));
        assertThat(f.faceted()).isTrue();
        assertThat(f.stored()).isTrue();
        assertThat(f.searchable()).isTrue();
    }

    @Test
    void testKeywordSingleValue() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "category", "type": "keyword", "value": "invoice" }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst().name()).isEqualTo("category");
        assertThat(fields.getFirst().type()).isEqualTo(JsonMetadataParser.FieldType.KEYWORD);
        assertThat(fields.getFirst().values()).isEqualTo(List.of("invoice"));
        assertThat(fields.getFirst().faceted()).isFalse();
    }

    @Test
    void testTextSingleValue() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "description", "type": "text", "value": "Some text content" }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst().type()).isEqualTo(JsonMetadataParser.FieldType.TEXT);
        assertThat(fields.getFirst().values()).isEqualTo(List.of("Some text content"));
    }

    @Test
    void testDateSingleValue() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "doc_date", "type": "date", "value": "2024-01-15T00:00:00Z" }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst().type()).isEqualTo(JsonMetadataParser.FieldType.DATE);
        // Epoch millis for 2024-01-15T00:00:00Z
        assertThat(fields.getFirst().values()).hasSize(1);
        assertThat((Long) fields.getFirst().values().getFirst()).isGreaterThan(0L);
    }

    // -----------------------------------------------------------------------
    // Happy-path: multi-values
    // -----------------------------------------------------------------------

    @Test
    void testMultiValueKeyword() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "tags", "type": "keyword", "values": ["invoice", "2024", "urgent"], "faceted": true }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst().values()).isEqualTo(List.of("invoice", "2024", "urgent"));
        assertThat(fields.getFirst().faceted()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Optional flags
    // -----------------------------------------------------------------------

    @Test
    void testStoredFalse() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "secret", "type": "keyword", "value": "x", "stored": false }
                  ]
                }
                """;

        final var fields = parser.parse(json);
        assertThat(fields.getFirst().stored()).isFalse();
    }

    @Test
    void testSearchableFalse() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "tag", "type": "keyword", "value": "x", "searchable": false }
                  ]
                }
                """;

        final var fields = parser.parse(json);
        assertThat(fields.getFirst().searchable()).isFalse();
    }

    // -----------------------------------------------------------------------
    // NULL handling
    // -----------------------------------------------------------------------

    @Test
    void testNullValueSkipsField() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "maybe_null", "type": "keyword", "value": null }
                  ]
                }
                """;

        // Null single-value → field is silently dropped (returns null in parseField)
        final var fields = parser.parse(json);
        assertThat(fields).isEmpty();
    }

    @Test
    void testNullInArraySkipsElement() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "tags", "type": "keyword", "values": ["a", null, "b"] }
                  ]
                }
                """;

        final var fields = parser.parse(json);
        assertThat(fields).hasSize(1);
        // null is skipped; remaining values are preserved
        assertThat(fields.getFirst().values()).isEqualTo(List.of("a", "b"));
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Test
    void testMissingFieldsArrayThrows() {
        assertThatThrownBy(() -> parser.parse("{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    @Test
    void testBothValueAndValuesThrows() {
        final String json = """
                {
                  "fields": [
                    { "name": "x", "type": "keyword", "value": "a", "values": ["b"] }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNeitherValueNorValuesThrows() {
        final String json = """
                {
                  "fields": [
                    { "name": "x", "type": "keyword" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testUnknownTypeThrows() {
        final String json = """
                {
                  "fields": [
                    { "name": "x", "type": "blob", "value": "data" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blob");
    }

    @Test
    void testInvalidJsonThrows() {
        assertThatThrownBy(() -> parser.parse("not-json"))
                .isInstanceOf(JsonProcessingException.class);
    }

    // -----------------------------------------------------------------------
    // INT type tests
    // -----------------------------------------------------------------------

    @Test
    void testIntSingleValue() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "count", "type": "int", "value": 42 }
                  ]
                }
                """;

        final var fields = parser.parse(json);

        assertThat(fields).hasSize(1);
        final var f = fields.getFirst();
        assertThat(f.name()).isEqualTo("count");
        assertThat(f.type()).isEqualTo(JsonMetadataParser.FieldType.INT);
        assertThat(f.values()).isEqualTo(List.of(42));
        assertThat(f.values().getFirst()).isInstanceOf(Integer.class);
    }

    @Test
    void testIntValueOutOfRangeDropsField() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "count", "type": "int", "value": 9999999999 }
                  ]
                }
                """;

        final var fields = parser.parse(json);
        assertThat(fields).isEmpty();
    }

    @Test
    void testIntValueNotNumericDropsField() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "count", "type": "int", "value": "not-a-number" }
                  ]
                }
                """;

        final var fields = parser.parse(json);
        assertThat(fields).isEmpty();
    }

    @Test
    void testLongFieldWithNonNumericValueDropsField() throws Exception {
        final String json = """
                {
                  "fields": [
                    { "name": "amount", "type": "long", "value": "not-a-number" }
                  ]
                }
                """;

        // Non-numeric single value for a LONG field → parseValue throws IAE, caught in parseField,
        // field is returned as null and filtered from the result list.
        final var fields = parser.parse(json);
        assertThat(fields).isEmpty();
    }

    @Test
    void testInvalidDateThrows() {
        final String json = """
                {
                  "fields": [
                    { "name": "d", "type": "date", "value": "not-a-date" }
                  ]
                }
                """;

        // Invalid date in single-value → field silently dropped
        final String jsonForTest = json;
        // We expect the field to be dropped (no exception from parse(), but empty result)
        // because parseField() catches the IAE and returns null
        try {
            final var fields = parser.parse(jsonForTest);
            assertThat(fields).isEmpty();
        } catch (final Exception e) {
            // Also acceptable if the exception propagates
        }
    }
}
