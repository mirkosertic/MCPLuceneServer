package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompoundLemmaSplittingFilter")
class CompoundLemmaSplittingFilterTest {

    record TokenWithPos(String term, int posInc) {}

    private static List<TokenWithPos> analyzeWithPositions(final String text) throws IOException {
        final List<TokenWithPos> result = new ArrayList<>();
        try (final Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(final String fieldName) {
                final Tokenizer tokenizer = new WhitespaceTokenizer();
                final TokenStream stream = new CompoundLemmaSplittingFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        }) {
            try (final TokenStream ts = analyzer.tokenStream("test", text)) {
                final CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
                final PositionIncrementAttribute posAttr = ts.addAttribute(PositionIncrementAttribute.class);
                ts.reset();
                while (ts.incrementToken()) {
                    result.add(new TokenWithPos(termAttr.toString(), posAttr.getPositionIncrement()));
                }
                ts.end();
            }
        }
        return result;
    }

    private static List<String> analyze(final String text) throws IOException {
        return analyzeWithPositions(text).stream().map(TokenWithPos::term).toList();
    }

    @Test
    @DisplayName("Plain token passes through unchanged")
    void plainTokenPassesThrough() throws IOException {
        assertThat(analyze("hello world")).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("Token with + is split into parts")
    void splitOnPlus() throws IOException {
        assertThat(analyze("in+der")).containsExactly("in", "der");
    }

    @Test
    @DisplayName("Multiple + separators")
    void multipleSegments() throws IOException {
        assertThat(analyze("a+b+c")).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("Leading + is handled")
    void leadingPlus() throws IOException {
        assertThat(analyze("+foo")).containsExactly("foo");
    }

    @Test
    @DisplayName("Trailing + is handled")
    void trailingPlus() throws IOException {
        assertThat(analyze("foo+")).containsExactly("foo");
    }

    @Test
    @DisplayName("Mixed tokens with and without +")
    void mixedTokens() throws IOException {
        assertThat(analyze("er in+der haus")).containsExactly("er", "in", "der", "haus");
    }

    @Test
    @DisplayName("Position increments are correct for split tokens")
    void positionIncrements() throws IOException {
        final List<TokenWithPos> tokens = analyzeWithPositions("er in+der haus");
        assertThat(tokens).containsExactly(
                new TokenWithPos("er", 1),
                new TokenWithPos("in", 1),    // first part: keeps original posInc
                new TokenWithPos("der", 1),   // second part: sequential
                new TokenWithPos("haus", 1)
        );
    }

    @Test
    @DisplayName("Consecutive + produces no empty tokens")
    void consecutivePlus() throws IOException {
        assertThat(analyze("a++b")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Only + produces original token (edge case)")
    void onlyPlus() throws IOException {
        // "+" alone → all parts empty → original "+" token passes through
        assertThat(analyze("+")).containsExactly("+");
    }
}
