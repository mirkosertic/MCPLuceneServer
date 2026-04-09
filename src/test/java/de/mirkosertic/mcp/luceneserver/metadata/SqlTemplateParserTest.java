package de.mirkosertic.mcp.luceneserver.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTemplateParserTest {

    private final SqlTemplateParser parser = new SqlTemplateParser();

    @Test
    void testSimpleParameter() {
        final SqlTemplateParser.ParsedTemplate result =
                parser.parse("SELECT * FROM docs WHERE id = :id");

        assertThat(result.sql()).isEqualTo("SELECT * FROM docs WHERE id = ?");
        assertThat(result.parameterNames()).isEqualTo(List.of("id"));
    }

    @Test
    void testMultipleParameters() {
        final SqlTemplateParser.ParsedTemplate result = parser.parse(
                "SELECT * FROM docs WHERE file_path = :file_path AND hash = :content_hash");

        assertThat(result.sql())
                .isEqualTo("SELECT * FROM docs WHERE file_path = ? AND hash = ?");
        assertThat(result.parameterNames()).isEqualTo(List.of("file_path", "content_hash"));
    }

    @Test
    void testNoParameters() {
        final SqlTemplateParser.ParsedTemplate result = parser.parse("SELECT * FROM docs");

        assertThat(result.sql()).isEqualTo("SELECT * FROM docs");
        assertThat(result.parameterNames()).isEmpty();
    }

    @Test
    void testUnderscoreInParameterName() {
        final SqlTemplateParser.ParsedTemplate result =
                parser.parse("SELECT m FROM meta WHERE doc_id = :doc_id");

        assertThat(result.parameterNames()).containsExactly("doc_id");
        assertThat(result.sql()).isEqualTo("SELECT m FROM meta WHERE doc_id = ?");
    }

    @Test
    void testSingleLetterParameter() {
        final SqlTemplateParser.ParsedTemplate result = parser.parse("SELECT * FROM t WHERE x = :x");

        assertThat(result.parameterNames()).containsExactly("x");
        assertThat(result.sql()).isEqualTo("SELECT * FROM t WHERE x = ?");
    }
}
