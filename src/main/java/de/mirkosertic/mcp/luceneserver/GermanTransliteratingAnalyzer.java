package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import java.io.Reader;

/**
 * Analyzer that transliterates German umlaut digraphs (ae→ä, oe→ö, ue→ü)
 * before standard Unicode normalization.
 *
 * <p>This analyzer is used for the {@code content_translit_de} shadow field.
 * The MappingCharFilter performs simple string replacements (ae→ä, oe→ö, ue→ü
 * and their uppercase variants), then StandardTokenizer + LowerCaseFilter + ICUFoldingFilter
 * normalize as usual.</p>
 *
 * <p>Net effect: both "Mueller" and "Müller" produce the same token "muller",
 * enabling German umlaut-digraph queries to match umlaut-containing documents.</p>
 *
 * <p><b>Known false positives:</b> "blue" → "blü" → "blu" (acceptable in a low-boost
 * shadow field where false positives are harmless).</p>
 */
public class GermanTransliteratingAnalyzer extends Analyzer {

    private static final NormalizeCharMap CHAR_MAP;

    static {
        final NormalizeCharMap.Builder builder = new NormalizeCharMap.Builder();
        // Lowercase digraphs
        builder.add("ae", "ä");
        builder.add("oe", "ö");
        builder.add("ue", "ü");
        // Title case digraphs
        builder.add("Ae", "Ä");
        builder.add("Oe", "Ö");
        builder.add("Ue", "Ü");
        // All-uppercase digraphs
        builder.add("AE", "Ä");
        builder.add("OE", "Ö");
        builder.add("UE", "Ü");
        CHAR_MAP = builder.build();
    }

    @Override
    protected Reader initReader(final String fieldName, final Reader reader) {
        return new MappingCharFilter(CHAR_MAP, reader);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final Tokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        stream = new ICUFoldingFilter(stream);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
