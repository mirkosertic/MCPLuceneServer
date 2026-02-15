package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer identical to {@link UnicodeNormalizingAnalyzer} but with an additional
 * {@link SnowballFilter} appended at the end of the token chain.
 *
 * <p>Token chain: {@code StandardTokenizer -> LowerCaseFilter -> ICUFoldingFilter -> SnowballFilter(languageName)}</p>
 *
 * <p>This analyzer is used for stemmed shadow fields ({@code content_stemmed_de},
 * {@code content_stemmed_en}), which store stemmed tokens alongside the original
 * {@code content} field. At search time a weighted OR query spans both fields so that
 * morphological variants (e.g. "Verträge" matching "Vertrag") are found without
 * sacrificing precision on exact matches. Highlighting always uses the unstemmed
 * {@code content} field, following the same pattern as {@link ReverseUnicodeNormalizingAnalyzer}
 * and the {@code content_reversed} field.</p>
 *
 * <p>Supported language names correspond to the Snowball stemmer names accepted by
 * {@link SnowballFilter}, for example {@code "German"} and {@code "English"}.</p>
 */
public class StemmedUnicodeNormalizingAnalyzer extends Analyzer {

    private final String languageName;

    /**
     * Creates a new {@code StemmedUnicodeNormalizingAnalyzer}.
     *
     * @param languageName the Snowball stemmer language name (e.g. {@code "German"}, {@code "English"})
     */
    public StemmedUnicodeNormalizingAnalyzer(final String languageName) {
        this.languageName = languageName;
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final Tokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        stream = new ICUFoldingFilter(stream);
        stream = new SnowballFilter(stream, languageName);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
