package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GermanTransliteratingAnalyzer}.
 */
@DisplayName("GermanTransliteratingAnalyzer")
class GermanTransliteratingAnalyzerTest {

    private final GermanTransliteratingAnalyzer analyzer = new GermanTransliteratingAnalyzer();

    private List<String> analyze(final String text) throws IOException {
        final List<String> tokens = new ArrayList<>();
        try (final TokenStream stream = analyzer.tokenStream("content_translit_de", text)) {
            final CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(attr.toString());
            }
            stream.end();
        }
        return tokens;
    }

    @Test
    @DisplayName("Mueller and Müller converge to same token")
    void muellerAndMullerConverge() throws IOException {
        final List<String> muellerTokens = analyze("Mueller");
        final List<String> mullerTokens = analyze("Müller");
        assertThat(muellerTokens).containsExactly("muller");
        assertThat(mullerTokens).containsExactly("muller");
    }

    @Test
    @DisplayName("ae transliteration: Kaese → kase")
    void aeTransliteration() throws IOException {
        assertThat(analyze("Kaese")).containsExactly("kase");
    }

    @Test
    @DisplayName("oe transliteration: Goethe → gothe")
    void oeTransliteration() throws IOException {
        assertThat(analyze("Goethe")).containsExactly("gothe");
    }

    @Test
    @DisplayName("Known false positive: blue → blu (acceptable)")
    void knownFalsePositive() throws IOException {
        assertThat(analyze("blue")).containsExactly("blu");
    }

    @Test
    @DisplayName("Straße unchanged (ICU handles ß)")
    void strasseHandledByIcu() throws IOException {
        assertThat(analyze("Straße")).containsExactly("strasse");
    }

    @Test
    @DisplayName("Uppercase variants: MUELLER → muller")
    void uppercaseVariants() throws IOException {
        assertThat(analyze("MUELLER")).containsExactly("muller");
    }

    @Test
    @DisplayName("Mixed case: Ueberfall → uberfall")
    void titleCaseUe() throws IOException {
        assertThat(analyze("Ueberfall")).containsExactly("uberfall");
    }

    @Test
    @DisplayName("Multiple tokens in sentence")
    void multipleTokens() throws IOException {
        final List<String> tokens = analyze("Herr Mueller und Frau Goertz");
        assertThat(tokens).containsExactly("herr", "muller", "und", "frau", "gortz");
    }
}
