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

/**
 * Unit tests for {@link StemmedUnicodeNormalizingAnalyzer}.
 *
 * <p>Verifies exact stem outputs produced by the token chain:
 * {@code StandardTokenizer -> LowerCaseFilter -> ICUFoldingFilter -> SnowballFilter(languageName)}
 *
 * <p>Each test group uses the {@link #analyzeToTokens(Analyzer, String)} helper to tokenize a
 * single word and collect the resulting token strings.  Where all inflected forms of a word
 * should share the same stem, the test asserts equality across stems.  For irregular forms
 * that the Snowball algorithm does not normalise (e.g. "ran", "paid"), the test documents the
 * actual behavior and asserts the output is non-empty.</p>
 */
@DisplayName("StemmedUnicodeNormalizingAnalyzer")
class StemmedUnicodeNormalizingAnalyzerTest {

    /**
     * Tokenizes {@code text} using {@code analyzer} against the {@code "content"} field and
     * returns the resulting token strings in order.
     */
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

    // ========== German ==========

    @Test
    @DisplayName("German: Vertrag, Vertrages, Verträge all stem to 'vertrag'")
    void germanContractFormsShareStem() throws IOException {
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemVertrag   = analyzeToTokens(analyzer, "Vertrag");
            final List<String> stemVertrages = analyzeToTokens(analyzer, "Vertrages");
            final List<String> stemVertraege = analyzeToTokens(analyzer, "Verträge");

            assertThat(stemVertrag).containsExactly("vertrag");
            assertThat(stemVertrages).isEqualTo(stemVertrag);
            assertThat(stemVertraege).isEqualTo(stemVertrag);
        }
    }

    @Test
    @DisplayName("German: Haus, Häuser, Hauses all stem to 'haus'")
    void germanHouseFormsShareStem() throws IOException {
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemHaus   = analyzeToTokens(analyzer, "Haus");
            final List<String> stemHaeuser = analyzeToTokens(analyzer, "Häuser");
            final List<String> stemHauses = analyzeToTokens(analyzer, "Hauses");

            assertThat(stemHaus).containsExactly("haus");
            assertThat(stemHaeuser).isEqualTo(stemHaus);
            assertThat(stemHauses).isEqualTo(stemHaus);
        }
    }

    @Test
    @DisplayName("German: suchen stems to 'such'; gesucht and suchte are stemmed differently by Snowball")
    void germanSearchVerbForms() throws IOException {
        // The German Snowball stemmer is not a full morphological analyser.
        // "suchen" -> "such", but "gesucht" -> "gesucht" and "suchte" -> "sucht" are not unified.
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemSuchen  = analyzeToTokens(analyzer, "suchen");
            final List<String> stemGesucht = analyzeToTokens(analyzer, "gesucht");
            final List<String> stemSuchte  = analyzeToTokens(analyzer, "suchte");

            assertThat(stemSuchen).containsExactly("such");
            // These irregular past forms produce their own stems — verify they are non-empty
            // and document the actual values so regressions are immediately visible.
            assertThat(stemGesucht).containsExactly("gesucht");
            assertThat(stemSuchte).containsExactly("sucht");
        }
    }

    @Test
    @DisplayName("German: Zahlung and Zahlungen both stem to 'zahlung'")
    void germanPaymentFormsShareStem() throws IOException {
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemZahlung   = analyzeToTokens(analyzer, "Zahlung");
            final List<String> stemZahlungen = analyzeToTokens(analyzer, "Zahlungen");

            assertThat(stemZahlung).containsExactly("zahlung");
            assertThat(stemZahlungen).isEqualTo(stemZahlung);
        }
    }

    // ========== English ==========

    @Test
    @DisplayName("English: contract, contracts, contracted, contracting all stem to 'contract'")
    void englishContractFormsShareStem() throws IOException {
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("English")) {
            final List<String> stemContract    = analyzeToTokens(analyzer, "contract");
            final List<String> stemContracts   = analyzeToTokens(analyzer, "contracts");
            final List<String> stemContracted  = analyzeToTokens(analyzer, "contracted");
            final List<String> stemContracting = analyzeToTokens(analyzer, "contracting");

            assertThat(stemContract).containsExactly("contract");
            assertThat(stemContracts).isEqualTo(stemContract);
            assertThat(stemContracted).isEqualTo(stemContract);
            assertThat(stemContracting).isEqualTo(stemContract);
        }
    }

    @Test
    @DisplayName("English: run and running share a stem; ran is an irregular past tense not unified by Snowball")
    void englishRunVerbForms() throws IOException {
        // Snowball English handles regular inflections but not suppletive (irregular) forms.
        // "run" -> "run", "running" -> "run", but "ran" -> "ran" (separate stem).
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("English")) {
            final List<String> stemRun     = analyzeToTokens(analyzer, "run");
            final List<String> stemRunning = analyzeToTokens(analyzer, "running");
            final List<String> stemRan     = analyzeToTokens(analyzer, "ran");

            assertThat(stemRun).containsExactly("run");
            assertThat(stemRunning).isEqualTo(stemRun);
            // "ran" is irregular — document the actual Snowball output
            assertThat(stemRan).containsExactly("ran");
            assertThat(stemRan).isNotEqualTo(stemRun);
        }
    }

    @Test
    @DisplayName("English: house, houses, housing all stem to 'hous'")
    void englishHouseFormsShareStem() throws IOException {
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("English")) {
            final List<String> stemHouse   = analyzeToTokens(analyzer, "house");
            final List<String> stemHouses  = analyzeToTokens(analyzer, "houses");
            final List<String> stemHousing = analyzeToTokens(analyzer, "housing");

            assertThat(stemHouse).containsExactly("hous");
            assertThat(stemHouses).isEqualTo(stemHouse);
            assertThat(stemHousing).isEqualTo(stemHouse);
        }
    }

    @Test
    @DisplayName("English: pay and payments share a stem; paid is an irregular past tense not unified by Snowball")
    void englishPaymentForms() throws IOException {
        // "pay" -> "pay", "payments" -> "payment" (Snowball keeps the -ment suffix root).
        // "paid" -> "paid" (irregular past — not unified with "pay").
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("English")) {
            final List<String> stemPay      = analyzeToTokens(analyzer, "pay");
            final List<String> stemPaid     = analyzeToTokens(analyzer, "paid");
            final List<String> stemPayments = analyzeToTokens(analyzer, "payments");

            assertThat(stemPay).containsExactly("pay");
            // "paid" is irregular — document the actual Snowball output
            assertThat(stemPaid).containsExactly("paid");
            assertThat(stemPaid).isNotEqualTo(stemPay);
            // "payments" strips the plural suffix giving "payment", not "pay"
            assertThat(stemPayments).containsExactly("payment");
            assertThat(stemPayments).isNotEqualTo(stemPay);
        }
    }

    // ========== ICU folding + stemming interaction ==========

    @Test
    @DisplayName("ICU folds ü->u before stemming: Müller and Muller produce the same stem")
    void icuFoldingUnifiesMuellerAndMuller() throws IOException {
        // ICUFoldingFilter converts ü to u, so both inputs reach the Snowball stemmer
        // as "muller", producing the same stem "mull".
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemMueller = analyzeToTokens(analyzer, "Müller");
            final List<String> stemMuller  = analyzeToTokens(analyzer, "Muller");

            assertThat(stemMueller).containsExactly("mull");
            assertThat(stemMuller).isEqualTo(stemMueller);
        }
    }

    @Test
    @DisplayName("ICU folds ä->a before stemming: Häuser and Hauser produce the same stem")
    void icuFoldingUnifiesHaeUserAndHauser() throws IOException {
        // ICUFoldingFilter converts ä to a, so "Häuser" and "Hauser" both reach the stemmer
        // as "hauser", and the German stemmer reduces both to "haus".
        try (final Analyzer analyzer = new StemmedUnicodeNormalizingAnalyzer("German")) {
            final List<String> stemHaeuser = analyzeToTokens(analyzer, "Häuser");
            final List<String> stemHauser  = analyzeToTokens(analyzer, "Hauser");

            assertThat(stemHaeuser).containsExactly("haus");
            assertThat(stemHauser).isEqualTo(stemHaeuser);
        }
    }
}
