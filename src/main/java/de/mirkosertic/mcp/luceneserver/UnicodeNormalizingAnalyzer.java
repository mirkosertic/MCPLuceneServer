package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Custom analyzer that applies Unicode normalization using ICU4J.
 *
 * This analyzer provides comprehensive Unicode handling including:
 * - NFKC normalization (compatibility decomposition + canonical composition)
 * - Case folding (Unicode-aware lowercase)
 * - Diacritic removal (ae → a, n~ → n)
 * - Ligature expansion (fi-ligature → fi, fl-ligature → fl)
 * - Full-width to half-width conversion (fullwidth A → A)
 *
 * This ensures that searches for "Muller" find "muller" and vice versa,
 * and PDF ligatures like the fi-ligature in "file" match searches for "file".
 *
 * <p><b>Breaking change note:</b> Switching to this analyzer invalidates any
 * existing Lucene index.  A full reindex is required after upgrading.</p>
 */
public class UnicodeNormalizingAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final Tokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        stream = new ICUFoldingFilter(stream);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
