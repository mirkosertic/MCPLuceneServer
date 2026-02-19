package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;

/**
 * A custom {@link QueryParser} that automatically expands exact phrase queries
 * to include proximity matches and enables adaptive scoring for prefix queries.
 *
 * <h2>Automatic Phrase Expansion</h2>
 * When users search for exact multi-word phrases (slop = 0), this parser automatically
 * expands them to a {@link BooleanQuery} that includes both:
 * <ul>
 *   <li>The exact phrase match with a boost (default: 2.0x) - highest ranking</li>
 *   <li>A proximity match with configurable slop (default: 3 words) - lower ranking</li>
 * </ul>
 *
 * <h2>Example Phrase Expansion</h2>
 * <pre>
 * User query:  "Domain Design"
 * Expands to:  ("Domain Design")^2.0 OR ("Domain Design"~3)
 * </pre>
 *
 * <h2>Adaptive Prefix Query Scoring</h2>
 * Prefix queries (e.g., {@code vertrag*}) use real BM25 scoring for specific prefixes
 * while maintaining constant scoring for broad prefixes to ensure performance.
 *
 * <h3>When Scoring is Enabled</h3>
 * <ul>
 *   <li>✅ Specific prefixes (>= 4 chars): {@code vertrag*}, {@code design*} → BM25 scoring</li>
 *   <li>❌ Broad prefixes (< 4 chars): {@code ver*}, {@code de*} → constant score (performance)</li>
 * </ul>
 *
 * <h3>Scoring Behavior Example</h3>
 * <pre>
 * Query: vertrag*  (>= 4 characters, scoring enabled)
 *
 * Results (ordered by BM25 score):
 * 1. "vertrag" (short, frequent)        → Score: 2.8  (HIGHEST)
 * 2. "vertrags"                         → Score: 1.9
 * 3. "vertragsklausel" (long, rare)     → Score: 1.2
 *
 * Query: ver*  (< 4 characters, constant score)
 *
 * Results (all have same score):
 * 1. "verarbeiten"  → Score: 1.0
 * 2. "vertrag"      → Score: 1.0
 * 3. "vereinfachen" → Score: 1.0
 * </pre>
 *
 * <h2>Configuration</h2>
 * Use the constructor to customize expansion behavior:
 * <pre>
 * // Default: slop=3, boost=2.0
 * QueryParser parser = new ProximityExpandingQueryParser("content", analyzer);
 *
 * // Custom: slop=5, boost=3.0
 * QueryParser parser = new ProximityExpandingQueryParser("content", analyzer, 5, 3.0f);
 * </pre>
 *
 * @see QueryParser
 * @see BooleanQuery
 * @see PhraseQuery
 * @see BoostQuery
 * @see PrefixQuery
 */
public class ProximityExpandingQueryParser extends QueryParser {

    /**
     * Default slop for proximity expansion (number of words allowed between phrase terms).
     */
    public static final int DEFAULT_PROXIMITY_SLOP = 3;

    /**
     * Default boost factor for exact phrase matches.
     */
    public static final float DEFAULT_EXACT_BOOST = 2.0f;

    /**
     * Minimum prefix length to enable BM25 scoring (shorter prefixes use constant score for performance).
     * Prefixes shorter than this threshold match too many terms and would be slow with scoring.
     */
    public static final int MIN_PREFIX_LENGTH_FOR_SCORING = 4;

    /**
     * Maximum number of terms to score in prefix expansion (rest uses constant score).
     * Uses TopTermsBlendedFreqScoringRewrite to score only the most frequent terms.
     */
    public static final int MAX_SCORED_PREFIX_TERMS = 50;

    private final int proximitySlop;
    private final float exactBoost;

    /**
     * Creates a new ProximityExpandingQueryParser with default settings.
     *
     * @param field    the default field to search
     * @param analyzer the analyzer to use for tokenization
     */
    public ProximityExpandingQueryParser(final String field, final Analyzer analyzer) {
        this(field, analyzer, DEFAULT_PROXIMITY_SLOP, DEFAULT_EXACT_BOOST);
    }

    /**
     * Creates a new ProximityExpandingQueryParser with custom settings.
     *
     * @param field          the default field to search
     * @param analyzer       the analyzer to use for tokenization
     * @param proximitySlop  the slop for proximity expansion (e.g., 3 = allow 3 words between terms)
     * @param exactBoost     the boost factor for exact matches (e.g., 2.0 = 2x score)
     */
    public ProximityExpandingQueryParser(final String field, final Analyzer analyzer,
                                          final int proximitySlop, final float exactBoost) {
        super(field, analyzer);
        this.proximitySlop = proximitySlop;
        this.exactBoost = exactBoost;
    }

    /**
     * Overrides phrase query creation to automatically expand exact multi-word phrases
     * to include proximity matches.
     *
     * <p>Expansion logic:</p>
     * <ul>
     *   <li>If slop = 0 (exact phrase) AND multi-word phrase → expand</li>
     *   <li>If slop > 0 (user specified slop) → don't expand (honor user's intent)</li>
     *   <li>If single word → don't expand (no benefit)</li>
     * </ul>
     *
     * @param field     the field to search
     * @param queryText the phrase query text
     * @param slop      the phrase slop (0 = exact, >0 = proximity)
     * @return the constructed query (either expanded BooleanQuery or original PhraseQuery)
     * @throws ParseException if query parsing fails
     */
    @Override
    protected Query getFieldQuery(final String field, final String queryText, final int slop)
            throws ParseException {

        // Get the base phrase query from parent
        final Query baseQuery = super.getFieldQuery(field, queryText, slop);

        // Only expand if:
        // 1. slop = 0 (exact phrase, not user-specified slop)
        // 2. baseQuery is a PhraseQuery (not a TermQuery for single words)
        // 3. The phrase has multiple terms
        if (slop == 0 && baseQuery instanceof PhraseQuery) {
            final PhraseQuery phraseQuery = (PhraseQuery) baseQuery;

            // Check if it's a multi-word phrase (more than 1 term)
            if (phraseQuery.getTerms().length > 1) {
                return expandToProximityQuery(phraseQuery, field, queryText);
            }
        }

        // No expansion needed - return original query
        return baseQuery;
    }

    /**
     * Expands an exact phrase query to include proximity matching.
     *
     * <p>Creates a {@link BooleanQuery} with two SHOULD clauses:</p>
     * <ol>
     *   <li>Exact phrase match with boost (e.g., 2.0x)</li>
     *   <li>Proximity phrase match with slop (e.g., ~3)</li>
     * </ol>
     *
     * @param exactPhrase the exact phrase query (slop = 0)
     * @param field       the field to search
     * @param queryText   the original query text
     * @return a BooleanQuery combining exact and proximity matches
     * @throws ParseException if proximity query creation fails
     */
    private Query expandToProximityQuery(final PhraseQuery exactPhrase, final String field,
                                          final String queryText) throws ParseException {

        // Create proximity phrase query with configurable slop
        final Query proximityPhrase = super.getFieldQuery(field, queryText, proximitySlop);

        // Boost the exact phrase match
        final Query boostedExact = new BoostQuery(exactPhrase, exactBoost);

        // Combine: (exact)^boost OR (proximity~slop)
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(boostedExact, BooleanClause.Occur.SHOULD);
        builder.add(proximityPhrase, BooleanClause.Occur.SHOULD);

        return builder.build();
    }

    /**
     * Overrides prefix query creation to enable adaptive scoring for specific prefix queries.
     *
     * <p>Prefix queries (e.g., {@code vertrag*}) normally use constant scoring where all
     * matches get the same score. This method enables real BM25 scoring for sufficiently
     * specific prefixes (>= 4 characters) while keeping constant scoring for broad prefixes
     * to maintain performance.</p>
     *
     * <h3>Scoring Strategy</h3>
     * <ul>
     *   <li><b>Specific prefixes (>= 4 chars):</b> Use {@link MultiTermQuery.TopTermsBlendedFreqScoringRewrite}
     *       to score the top 50 most frequent matching terms with BM25. Shorter/more frequent terms
     *       rank higher.</li>
     *   <li><b>Broad prefixes (< 4 chars):</b> Use default constant scoring for performance.
     *       Short prefixes like {@code ver*} can match hundreds of terms.</li>
     * </ul>
     *
     * <h3>Examples</h3>
     * <pre>
     * Query: vertrag*  (>= 4 chars)  → BM25 scoring enabled
     * Results:
     *   - "vertrag" (short, frequent)      → 2.8 (highest)
     *   - "vertrags"                       → 1.9
     *   - "vertragsklausel" (long, rare)   → 1.2
     *
     * Query: ver*  (< 4 chars)  → Constant scoring (performance)
     * Results:
     *   - All matches get score: 1.0
     * </pre>
     *
     * @param field       field to search
     * @param termStr     prefix string (e.g., "vertrag")
     * @return query with appropriate scoring strategy
     * @throws ParseException if query parsing fails
     */
    @Override
    protected Query getPrefixQuery(final String field, final String termStr)
            throws ParseException {

        // Enable scoring only for specific prefixes (>= 4 chars)
        // Shorter prefixes match too many terms and would be slow with scoring
        if (termStr.length() >= MIN_PREFIX_LENGTH_FOR_SCORING) {
            // Use TopTermsBlendedFreqScoringRewrite to:
            // 1. Score only the top N most frequent terms (performance)
            // 2. Blend frequencies for better ranking (BM25)
            // 3. Ignore very rare terms (noise reduction)
            return new PrefixQuery(
                new Term(field, termStr),
                new MultiTermQuery.TopTermsBlendedFreqScoringRewrite(MAX_SCORED_PREFIX_TERMS)
            );
        }

        // Default behavior for short prefixes (< 4 chars): constant score for performance
        return super.getPrefixQuery(field, termStr);
    }
}
