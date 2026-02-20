package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenNLPLemmatizingAnalyzer}.
 *
 * <p>Verifies lemma outputs produced by the token chain:
 * {@code OpenNLPTokenizer → OpenNLPPOSFilter → OpenNLPLemmatizerFilter → LowerCaseFilter → ICUFoldingFilter}
 *
 * <p>Tests cover both sentence-aware mode (for indexing) and query mode (StandardTokenizer,
 * for short queries without sentence context).</p>
 */
@DisplayName("OpenNLPLemmatizingAnalyzer")
class OpenNLPLemmatizingAnalyzerTest {

    private static List<String> analyzeToTokens(final Analyzer analyzer, final String text) throws IOException {
        final List<String> tokens = new ArrayList<>();
        try (final TokenStream tokenStream = analyzer.tokenStream("content", text)) {
            final CharTermAttribute termAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                tokens.add(termAttr.toString());
            }
            tokenStream.end();
        }
        return tokens;
    }

    // ========== German sentence-aware (indexing mode) ==========

    @Test
    @DisplayName("German: Vertrag forms in sentence context all lemmatize to 'vertrag'")
    void germanContractFormsInSentence() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer,
                    "Der Vertrag wurde unterschrieben. Die Bedingungen des Vertrages sind klar.");
            assertThat(tokens).contains("vertrag");
            // Both "Vertrag" and "Vertrages" lemmatize to "vertrag"
            assertThat(tokens.stream().filter("vertrag"::equals).count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("German: Haus/Häuser/Hauses in sentence context all lemmatize to 'haus'")
    void germanHouseFormsInSentence() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            assertThat(analyzeToTokens(analyzer, "Das Haus ist groß.")).contains("haus");
            assertThat(analyzeToTokens(analyzer, "Die Häuser sind renoviert.")).contains("haus");
            assertThat(analyzeToTokens(analyzer, "Des Hauses Wert steigt.")).contains("haus");
        }
    }

    @Test
    @DisplayName("German: irregular verb 'gehen' - ging lemmatizes to 'gehen'")
    void germanIrregularVerbGehen() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            assertThat(analyzeToTokens(analyzer, "Er ging nach Hause.")).contains("gehen");
        }
    }

    @Test
    @DisplayName("German: verb suchen/gesucht/suchte all lemmatize to 'suchen'")
    void germanSearchVerbForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            assertThat(analyzeToTokens(analyzer, "Wir suchen einen Mitarbeiter.")).contains("suchen");
            assertThat(analyzeToTokens(analyzer, "Er hat gesucht.")).contains("suchen");
        }
    }

    @Test
    @DisplayName("German: Zahlungen lemmatizes to 'zahlung'")
    void germanPaymentForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            assertThat(analyzeToTokens(analyzer, "Die Zahlungen wurden verbucht.")).contains("zahlung");
        }
    }

    // ========== English sentence-aware (indexing mode) ==========

    @Test
    @DisplayName("English: contract/contracts/contracted all lemmatize to 'contract'")
    void englishContractForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "The contract was signed.")).contains("contract");
            assertThat(analyzeToTokens(analyzer, "Several contracts were reviewed.")).contains("contract");
            assertThat(analyzeToTokens(analyzer, "He contracted a supplier.")).contains("contract");
        }
    }

    @Test
    @DisplayName("English: irregular verb run/ran/running all lemmatize to 'run'")
    void englishRunVerbForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "They run every morning.")).contains("run");
            assertThat(analyzeToTokens(analyzer, "She ran to the bus.")).contains("run");
            assertThat(analyzeToTokens(analyzer, "The application is running.")).contains("run");
        }
    }

    @Test
    @DisplayName("English: irregular verb go/went lemmatizes correctly")
    void englishGoVerbForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "He went to the store.")).contains("go");
        }
    }

    @Test
    @DisplayName("English: irregular verb see/saw lemmatizes correctly")
    void englishSeeVerbForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "I saw the results.")).contains("see");
        }
    }

    @Test
    @DisplayName("English: pay/paid both lemmatize to 'pay'")
    void englishPayForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "Please pay the balance.")).contains("pay");
            assertThat(analyzeToTokens(analyzer, "She paid the invoice.")).contains("pay");
        }
    }

    @Test
    @DisplayName("English: analysis/analyses both lemmatize to 'analysis'")
    void englishAnalysisForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "The analysis revealed trends.")).contains("analysis");
            assertThat(analyzeToTokens(analyzer, "Multiple analyses were performed.")).contains("analysis");
        }
    }

    @Test
    @DisplayName("English: house/houses lemmatize to 'house', housing stays as 'housing'")
    void englishHouseForms() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            assertThat(analyzeToTokens(analyzer, "The house is large.")).contains("house");
            assertThat(analyzeToTokens(analyzer, "The houses were sold.")).contains("house");
            // "housing" is a distinct noun, NOT conflated with "house"
            assertThat(analyzeToTokens(analyzer, "Affordable housing remains critical.")).contains("housing");
            assertThat(analyzeToTokens(analyzer, "Affordable housing remains critical.")).doesNotContain("house");
        }
    }

    // ========== Query mode (no sentence detection) ==========

    @Test
    @DisplayName("Query mode: German single-word nouns lemmatize correctly")
    void queryModeGermanNouns() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de", false)) {
            assertThat(analyzeToTokens(analyzer, "Vertrag")).containsExactly("vertrag");
            assertThat(analyzeToTokens(analyzer, "Vertrages")).containsExactly("vertrag");
            assertThat(analyzeToTokens(analyzer, "Häuser")).containsExactly("haus");
        }
    }

    @Test
    @DisplayName("Query mode: German verbs lemmatize correctly")
    void queryModeGermanVerbs() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de", false)) {
            assertThat(analyzeToTokens(analyzer, "suchen")).containsExactly("suchen");
            assertThat(analyzeToTokens(analyzer, "gesucht")).containsExactly("suchen");
            assertThat(analyzeToTokens(analyzer, "suchte")).containsExactly("suchen");
        }
    }

    @Test
    @DisplayName("Query mode: English irregular forms lemmatize correctly")
    void queryModeEnglishIrregulars() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en", false)) {
            assertThat(analyzeToTokens(analyzer, "ran")).containsExactly("run");
            assertThat(analyzeToTokens(analyzer, "running")).containsExactly("run");
            assertThat(analyzeToTokens(analyzer, "paid")).containsExactly("pay");
            assertThat(analyzeToTokens(analyzer, "analyses")).containsExactly("analysis");
            assertThat(analyzeToTokens(analyzer, "contracted")).containsExactly("contract");
            assertThat(analyzeToTokens(analyzer, "houses")).containsExactly("house");
        }
    }

    // ========== ICU folding interaction ==========

    @Test
    @DisplayName("ICU folds umlauts after lemmatization: Müller → muller")
    void icuFoldingAfterLemmatization() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de", false)) {
            final List<String> stemMueller = analyzeToTokens(analyzer, "Müller");
            final List<String> stemMuller = analyzeToTokens(analyzer, "Muller");
            // Both should produce the same output after ICU folding
            assertThat(stemMueller).isEqualTo(stemMuller);
        }
    }

    // ========== Error handling ==========

    @Test
    @DisplayName("Unsupported language throws IllegalArgumentException")
    void unsupportedLanguageThrows() {
        assertThatThrownBy(() -> new OpenNLPLemmatizingAnalyzer("fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported language");
    }

    @Test
    @DisplayName("Analyzer can be created for both supported languages")
    void analyzerCreation() {
        try (final Analyzer en = new OpenNLPLemmatizingAnalyzer("en");
             final Analyzer de = new OpenNLPLemmatizingAnalyzer("de")) {
            assertThat(en).isNotNull();
            assertThat(de).isNotNull();
        }
    }

    // ========== Punctuation filtering ==========

    @Test
    @DisplayName("German: punctuation tokens are filtered out")
    void germanPunctuationFiltered() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer, "Er ging, sie blieb.");
            assertThat(tokens).doesNotContain(",", ".", ";", ":", "!", "?", "(", ")", "\"", "-", "...", "&");
            assertThat(tokens).contains("gehen", "bleiben");
        }
    }

    @Test
    @DisplayName("English: punctuation tokens are filtered out")
    void englishPunctuationFiltered() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            final List<String> tokens = analyzeToTokens(analyzer, "He went, she stayed.");
            assertThat(tokens).doesNotContain(",", ".", ";", ":", "!", "?");
            assertThat(tokens).contains("go", "stay");
        }
    }

    @Test
    @DisplayName("German: ampersand symbol is filtered out")
    void germanAmpersandFiltered() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer, "Forschung & Entwicklung sind wichtig.");
            assertThat(tokens).doesNotContain("&");
            assertThat(tokens).contains("forschung", "entwicklung");
        }
    }

    @Test
    @DisplayName("German: R&D stays as single token (not split)")
    void germanRAndDStaysAsToken() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer, "R&D Abteilung im Haus.");
            assertThat(tokens).contains("r&d");
        }
    }

    // ========== Compound lemma splitting ==========

    @Test
    @DisplayName("German: contraction 'im' splits to 'in' and 'der'")
    void germanContractionImSplits() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer, "Er ist im Haus.");
            assertThat(tokens).contains("in");
            assertThat(tokens).doesNotContain("in+der");
        }
    }

    @Test
    @DisplayName("German: contraction 'zum' splits to 'zu' and 'der'")
    void germanContractionZumSplits() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            final List<String> tokens = analyzeToTokens(analyzer, "Er ging zum Haus.");
            assertThat(tokens).contains("zu");
            assertThat(tokens).doesNotContain("zu+der");
        }
    }

    @Test
    @DisplayName("German: all common contractions are properly split")
    void germanAllContractionsHandled() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            // Test all common German contractions
            assertThat(analyzeToTokens(analyzer, "Er ist im Haus.")).doesNotContain("in+der");
            assertThat(analyzeToTokens(analyzer, "Er ging zum Markt.")).doesNotContain("zu+der");
            assertThat(analyzeToTokens(analyzer, "Sie ging zur Schule.")).doesNotContain("zu+der");
            assertThat(analyzeToTokens(analyzer, "Er ging ins Haus.")).doesNotContain("in+der");
            assertThat(analyzeToTokens(analyzer, "Er steht am Fenster.")).doesNotContain("an+der");
            assertThat(analyzeToTokens(analyzer, "Er war beim Arzt.")).doesNotContain("bei+der");
            assertThat(analyzeToTokens(analyzer, "Er kam vom Markt.")).doesNotContain("von+der");
        }
    }

    // ========== Query mode: punctuation filtering ==========

    @Test
    @DisplayName("Query mode: punctuation does not appear in tokens")
    void queryModePunctuationFiltered() throws IOException {
        try (final Analyzer analyzer = new OpenNLPLemmatizingAnalyzer("de", false)) {
            // StandardTokenizer already strips punctuation, but TypeTokenFilter is harmless
            final List<String> tokens = analyzeToTokens(analyzer, "Vertrag, Haus");
            assertThat(tokens).doesNotContain(",");
        }
    }
}
