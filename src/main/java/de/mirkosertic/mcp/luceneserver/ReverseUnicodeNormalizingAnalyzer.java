package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.reverse.ReverseStringFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer identical to {@link UnicodeNormalizingAnalyzer} but with an additional
 * {@link ReverseStringFilter} appended at the end of the token chain.
 *
 * <p>Token chain: {@code StandardTokenizer -> LowerCaseFilter -> ICUFoldingFilter -> ReverseStringFilter}</p>
 *
 * <p>This analyzer is used for the {@code content_reversed} field, which stores
 * reversed tokens so that leading wildcard queries ({@code *vertrag}) can be
 * transparently rewritten as trailing wildcard queries on the reversed field
 * ({@code gartrev*}), avoiding the costly expansion of leading wildcards on the
 * forward index.</p>
 */
public class ReverseUnicodeNormalizingAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final Tokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        stream = new ICUFoldingFilter(stream);
        stream = new ReverseStringFilter(stream);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
