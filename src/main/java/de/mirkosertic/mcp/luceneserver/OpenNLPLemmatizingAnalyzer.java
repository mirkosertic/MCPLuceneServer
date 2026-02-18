package de.mirkosertic.mcp.luceneserver;

import com.github.benmanes.caffeine.cache.Cache;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerModel;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPPOSFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPTokenizer;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPPOSTaggerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPSentenceDetectorOp;
import org.apache.lucene.analysis.opennlp.tools.NLPTokenizerOp;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.SentenceAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Analyzer that uses OpenNLP for true dictionary-based lemmatization instead of
 * rule-based Snowball stemming.
 *
 * <p>Two modes of operation are supported:</p>
 * <ul>
 *   <li><b>Sentence-aware (for indexing)</b>: Uses {@code OpenNLPTokenizer} with sentence
 *       detection for accurate POS tagging of long texts.
 *       Chain: {@code OpenNLPTokenizer → OpenNLPPOSFilter → OpenNLPLemmatizerFilter
 *       → LowerCaseFilter → ICUFoldingFilter}</li>
 *   <li><b>Simple (for query time)</b>: Uses {@code StandardTokenizer} without sentence
 *       detection, treating the entire input as a single sentence. This provides better
 *       POS tagging for short queries (1-3 words) where the sentence detector may
 *       misclassify the input, leading to incorrect lemmatization.
 *       Chain: {@code StandardTokenizer → OpenNLPPOSFilter → OpenNLPLemmatizerFilter
 *       → LowerCaseFilter → ICUFoldingFilter}</li>
 * </ul>
 *
 * <p>OpenNLP lemmatization handles irregular morphological forms that Snowball cannot:
 * {@code ran→run}, {@code went→go}, {@code ging→gehen}, {@code analyses→analysis}.
 * LowerCase and ICUFolding are applied <em>after</em> lemmatization because the
 * lemmatizer requires original casing and POS tags to work correctly.</p>
 *
 * <p>This analyzer is used for lemmatized shadow fields ({@code content_lemma_de},
 * {@code content_lemma_en}), which store lemmatized tokens alongside the original
 * {@code content} field. At search time a weighted OR query spans both fields so that
 * morphological variants are found without sacrificing precision on exact matches.</p>
 *
 * <p>Supported languages: {@code "en"} (English) and {@code "de"} (German).</p>
 */
public class OpenNLPLemmatizingAnalyzer extends Analyzer {

    /**
     * Maps language code to its Universal Dependencies treebank identifier used
     * in the OpenNLP model file names.
     */
    private static final Map<String, String> TREEBANK_BY_LANGUAGE = Map.of(
            "en", "ewt",
            "de", "gsd"
    );

    /**
     * Model version prefix embedded in the OpenNLP model file names.
     */
    private static final String MODEL_VERSION = "1.2-2.5.0";

    /**
     * Maximum number of (token, POS tag) → lemma entries in the shared cache per language.
     */
    private static final int MAX_CACHE_SIZE = 1_500_000;

    private final SentenceModel sentenceModel;
    private final TokenizerModel tokenizerModel;
    private final POSModel posModel;
    private final LemmatizerModel lemmatizerModel;
    private final boolean useSentenceDetection;
    private final LemmatizerCacheStats cacheStats;
    private final Cache<CachedNLPLemmatizerOp.TokenPosPair, String> sharedLemmatizerCache;

    /**
     * Creates a new sentence-aware {@code OpenNLPLemmatizingAnalyzer} (for indexing).
     *
     * @param languageCode ISO 639-1 language code ({@code "en"} or {@code "de"})
     */
    public OpenNLPLemmatizingAnalyzer(final String languageCode) {
        this(languageCode, true);
    }

    /**
     * Creates a new {@code OpenNLPLemmatizingAnalyzer} for the given language.
     *
     * <p>Loads four OpenNLP models from the classpath at construction time. The model
     * JARs must be on the classpath (provided via Maven dependencies). Fails fast
     * with {@link IllegalStateException} if any model is not found.</p>
     *
     * @param languageCode          ISO 639-1 language code ({@code "en"} or {@code "de"})
     * @param useSentenceDetection  {@code true} for sentence-aware mode (indexing),
     *                              {@code false} for simple mode (query time)
     * @throws IllegalArgumentException if the language is not supported
     * @throws IllegalStateException    if a model resource cannot be found on the classpath
     * @throws UncheckedIOException     if a model resource cannot be read
     */
    public OpenNLPLemmatizingAnalyzer(final String languageCode, final boolean useSentenceDetection) {
        final String treebank = TREEBANK_BY_LANGUAGE.get(languageCode);
        if (treebank == null) {
            throw new IllegalArgumentException(
                    "Unsupported language for OpenNLP lemmatization: " + languageCode
                            + ". Supported: " + TREEBANK_BY_LANGUAGE.keySet());
        }

        this.useSentenceDetection = useSentenceDetection;
        this.cacheStats = new LemmatizerCacheStats();
        this.sharedLemmatizerCache = CachedNLPLemmatizerOp.createSharedCache(MAX_CACHE_SIZE, this.cacheStats);

        try {
            this.sentenceModel = new SentenceModel(
                    loadModelResource(languageCode, treebank, "sentence"));
            this.tokenizerModel = new TokenizerModel(
                    loadModelResource(languageCode, treebank, "tokens"));
            this.posModel = new POSModel(
                    loadModelResource(languageCode, treebank, "pos"));
            this.lemmatizerModel = new LemmatizerModel(
                    loadModelResource(languageCode, treebank, "lemmas"));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to load OpenNLP models for language: " + languageCode, e);
        }
    }

    private OpenNLPLemmatizingAnalyzer(final SentenceModel sentenceModel,
                                       final TokenizerModel tokenizerModel,
                                       final POSModel posModel,
                                       final LemmatizerModel lemmatizerModel,
                                       final boolean useSentenceDetection,
                                       final LemmatizerCacheStats cacheStats,
                                       final Cache<CachedNLPLemmatizerOp.TokenPosPair, String> sharedLemmatizerCache) {
        this.sentenceModel = sentenceModel;
        this.tokenizerModel = tokenizerModel;
        this.posModel = posModel;
        this.lemmatizerModel = lemmatizerModel;
        this.useSentenceDetection = useSentenceDetection;
        this.cacheStats = cacheStats;
        this.sharedLemmatizerCache = sharedLemmatizerCache;
    }

    /**
     * Creates a new analyzer that shares the loaded models from this instance
     * but uses the specified sentence detection mode.
     *
     * <p><strong>Important:</strong> The new analyzer gets its own independent cache and stats,
     * because sentence-aware (indexing) and sentence-unaware (query-time) modes may assign
     * different POS tags to the same token, leading to different lemma mappings. Sharing the
     * cache between these two modes would cause incorrect query-time results (e.g. a gerund
     * lemmatized as a noun during indexing overwriting the correct verb lemma used at query time).
     * </p>
     *
     * @param useSentenceDetection {@code true} for sentence-aware mode (indexing),
     *                              {@code false} for simple mode (query time)
     * @return a new analyzer with its own independent cache, sharing only the loaded models
     */
    public OpenNLPLemmatizingAnalyzer withSentenceDetection(final boolean useSentenceDetection) {
        final LemmatizerCacheStats newStats = new LemmatizerCacheStats();
        final Cache<CachedNLPLemmatizerOp.TokenPosPair, String> newCache =
                CachedNLPLemmatizerOp.createSharedCache(MAX_CACHE_SIZE, newStats);
        return new OpenNLPLemmatizingAnalyzer(
                this.sentenceModel, this.tokenizerModel,
                this.posModel, this.lemmatizerModel, useSentenceDetection,
                newStats, newCache);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        try {
            final Tokenizer tokenizer;

            if (useSentenceDetection) {
                final OpenNLPTokenizer nlpTokenizer = new OpenNLPTokenizer(
                        AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY,
                        new NLPSentenceDetectorOp(sentenceModel),
                        new NLPTokenizerOp(tokenizerModel));
                // OpenNLPPOSFilter.assignTokenTypes() uses getAttribute(TypeAttribute.class) on
                // cloned attribute sources, so TypeAttribute must be present in the shared attribute
                // source before POS tagging. OpenNLPTokenizer does not add it, so we do it here.
                nlpTokenizer.addAttribute(TypeAttribute.class);
                tokenizer = nlpTokenizer;
            } else {
                // Query-time mode: StandardTokenizer treats all input as a single sentence,
                // giving the POS tagger full context even for short queries.
                final StandardTokenizer stdTokenizer = new StandardTokenizer();
                stdTokenizer.addAttribute(TypeAttribute.class);
                stdTokenizer.addAttribute(SentenceAttribute.class);
                tokenizer = stdTokenizer;
            }

            TokenStream stream = new OpenNLPPOSFilter(tokenizer, new NLPPOSTaggerOp(posModel));
            final NLPLemmatizerOp baseLemmatizer = new NLPLemmatizerOp(null, lemmatizerModel);
            final CachedNLPLemmatizerOp cachedLemmatizer = new CachedNLPLemmatizerOp(
                    baseLemmatizer, lemmatizerModel, cacheStats, sharedLemmatizerCache);
            stream = new OpenNLPLemmatizerFilter(stream, cachedLemmatizer);
            stream = new LowerCaseFilter(stream);
            stream = new ICUFoldingFilter(stream);

            return new TokenStreamComponents(tokenizer, stream);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create OpenNLP token stream components", e);
        }
    }

    /**
     * Returns the cache statistics for monitoring lemmatizer performance.
     *
     * @return the {@link LemmatizerCacheStats} instance for this analyzer
     */
    public LemmatizerCacheStats getCacheStats() {
        return cacheStats;
    }

    /**
     * Loads an OpenNLP model resource from the classpath.
     *
     * @param lang     the language code (e.g. "en", "de")
     * @param treebank the UD treebank identifier (e.g. "ewt", "gsd")
     * @param type     the model type (e.g. "sentence", "tokens", "pos", "lemmas")
     * @return an InputStream for the model resource
     * @throws IllegalStateException if the resource is not found on the classpath
     */
    private InputStream loadModelResource(final String lang, final String treebank, final String type) {
        final String resourcePath = "/opennlp-" + lang + "-ud-" + treebank + "-" + type
                + "-" + MODEL_VERSION + ".bin";
        final InputStream stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException(
                    "OpenNLP model not found on classpath: " + resourcePath
                            + ". Ensure the corresponding opennlp-models-* Maven dependency is present.");
        }
        return stream;
    }
}
