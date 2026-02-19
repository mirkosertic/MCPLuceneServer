package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProximityExpandingQueryParser}.
 * Verifies automatic phrase query expansion behavior.
 */
@DisplayName("ProximityExpandingQueryParser Tests")
class ProximityExpandingQueryParserTest {

    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    @Test
    @DisplayName("Multi-word phrase should expand to boosted exact + proximity")
    void testMultiWordPhraseExpansion() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Design\"");

        // Should be a BooleanQuery with 2 SHOULD clauses
        assertInstanceOf(BooleanQuery.class, query, "Multi-word exact phrase should expand to BooleanQuery");

        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        assertEquals(2, shouldClauses.size(), "Should have 2 SHOULD clauses (exact + proximity)");
    }

    @Test
    @DisplayName("Single-word phrase should NOT expand")
    void testSingleWordPhraseNotExpanded() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Design\"");

        // Single-word "phrase" should remain a TermQuery (not PhraseQuery, not BooleanQuery)
        assertInstanceOf(TermQuery.class, query, "Single-word phrase should not expand to BooleanQuery");
    }

    @Test
    @DisplayName("User-specified slop should be honored (no expansion)")
    void testUserSpecifiedSlopHonored() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Design\"~5");

        // Should remain a PhraseQuery with user's slop
        assertInstanceOf(PhraseQuery.class, query, "User-specified slop should not be expanded");

        final PhraseQuery phraseQuery = (PhraseQuery) query;
        assertEquals(5, phraseQuery.getSlop(),
                "Should preserve user-specified slop of 5");
    }

    @Test
    @DisplayName("Expanded query structure should be correct")
    void testCorrectQueryStructure() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Design\"");

        assertInstanceOf(BooleanQuery.class, query);
        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        assertEquals(2, shouldClauses.size(), "Should have 2 SHOULD clauses");

        // Find the boosted exact phrase and proximity phrase (order may vary)
        BoostQuery boostedExact = null;
        PhraseQuery proximityPhrase = null;

        for (final Query clause : shouldClauses) {
            if (clause instanceof BoostQuery) {
                boostedExact = (BoostQuery) clause;
            } else if (clause instanceof PhraseQuery) {
                proximityPhrase = (PhraseQuery) clause;
            }
        }

        assertNotNull(boostedExact, "Should have boosted exact phrase");
        assertNotNull(proximityPhrase, "Should have proximity phrase");

        // Verify boosted exact phrase
        assertInstanceOf(PhraseQuery.class, boostedExact.getQuery(), "Boosted query should wrap a PhraseQuery");

        final PhraseQuery exactPhrase = (PhraseQuery) boostedExact.getQuery();
        assertEquals(0, exactPhrase.getSlop(),
                "Exact phrase should have slop=0");

        // Verify proximity phrase
        assertEquals(3, proximityPhrase.getSlop(),
                "Proximity phrase should have default slop=3");
    }

    @Test
    @DisplayName("Boost should be applied correctly")
    void testCorrectBoostApplied() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Design\"");

        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        // Find boosted and unboosted queries (order may vary)
        BoostQuery boostedExact = null;
        Query unboostedProximity = null;

        for (final Query clause : shouldClauses) {
            if (clause instanceof BoostQuery) {
                boostedExact = (BoostQuery) clause;
            } else {
                unboostedProximity = clause;
            }
        }

        assertNotNull(boostedExact, "Should have a boosted query");
        assertEquals(2.0f, boostedExact.getBoost(), 0.001f,
                "Exact phrase should have default boost of 2.0");

        assertNotNull(unboostedProximity, "Should have an unboosted query");
        assertInstanceOf(PhraseQuery.class, unboostedProximity, "Proximity phrase should be PhraseQuery without explicit boost");
    }

    @Test
    @DisplayName("Non-phrase queries should work normally")
    void testNonPhraseQueriesUnaffected() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        // Simple term query (no quotes)
        final Query query = parser.parse("Design");

        assertInstanceOf(TermQuery.class, query, "Simple term query should remain TermQuery");

        final TermQuery termQuery = (TermQuery) query;
        assertEquals("design", termQuery.getTerm().text(),
                "Term should be lowercased by StandardAnalyzer");
    }

    @Test
    @DisplayName("Complex queries should expand phrases correctly")
    void testComplexQueries() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        // Phrase + term query
        final Query query = parser.parse("\"Domain Design\" AND architecture");

        // Should be a BooleanQuery with 2 MUST clauses: expanded phrase + term
        assertInstanceOf(BooleanQuery.class, query);
        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> mustClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.MUST));

        assertEquals(2, mustClauses.size(), "Should have 2 MUST clauses (phrase + term)");

        // Find expanded phrase and term (order may vary)
        BooleanQuery expandedPhrase = null;
        TermQuery termQuery = null;

        for (final Query clause : mustClauses) {
            if (clause instanceof BooleanQuery) {
                expandedPhrase = (BooleanQuery) clause;
            } else if (clause instanceof TermQuery) {
                termQuery = (TermQuery) clause;
            }
        }

        assertNotNull(expandedPhrase, "Should have expanded phrase (BooleanQuery)");
        assertNotNull(termQuery, "Should have term query");
    }

    @Test
    @DisplayName("Custom slop and boost parameters should work")
    void testCustomSlopAndBoost() throws ParseException {
        // Create parser with custom slop=5, boost=3.0
        final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer, 5, 3.0f);

        final Query query = parser.parse("\"Domain Design\"");

        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        // Find boosted and unboosted queries (order may vary)
        BoostQuery boostedExact = null;
        PhraseQuery proximityPhrase = null;

        for (final Query clause : shouldClauses) {
            if (clause instanceof BoostQuery) {
                boostedExact = (BoostQuery) clause;
            } else if (clause instanceof PhraseQuery) {
                proximityPhrase = (PhraseQuery) clause;
            }
        }

        // Check custom boost
        assertNotNull(boostedExact);
        assertEquals(3.0f, boostedExact.getBoost(), 0.001f,
                "Should use custom boost of 3.0");

        // Check custom slop
        assertNotNull(proximityPhrase);
        assertEquals(5, proximityPhrase.getSlop(),
                "Should use custom slop of 5");
    }

    @Test
    @DisplayName("Three-word phrase should expand correctly")
    void testThreeWordPhrase() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Driven Design\"");

        // Should expand to BooleanQuery
        assertInstanceOf(BooleanQuery.class, query);

        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        assertEquals(2, shouldClauses.size());

        // Find boosted and unboosted queries (order may vary)
        BoostQuery boostedExact = null;
        PhraseQuery proximityPhrase = null;

        for (final Query clause : shouldClauses) {
            if (clause instanceof BoostQuery) {
                boostedExact = (BoostQuery) clause;
            } else if (clause instanceof PhraseQuery) {
                proximityPhrase = (PhraseQuery) clause;
            }
        }

        // Verify both clauses have 3 terms
        assertNotNull(boostedExact);
        final PhraseQuery exactPhrase = (PhraseQuery) boostedExact.getQuery();
        assertEquals(3, exactPhrase.getTerms().length,
                "Exact phrase should have 3 terms");

        assertNotNull(proximityPhrase);
        assertEquals(3, proximityPhrase.getTerms().length,
                "Proximity phrase should have 3 terms");
    }

    @Test
    @DisplayName("OR query with multiple phrases should expand each phrase")
    void testMultiplePhrases() throws ParseException {
        final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

        final Query query = parser.parse("\"Domain Design\" OR \"Test Driven\"");

        assertInstanceOf(BooleanQuery.class, query);
        final BooleanQuery booleanQuery = (BooleanQuery) query;
        final java.util.List<Query> shouldClauses = new java.util.ArrayList<>(booleanQuery.getClauses(BooleanClause.Occur.SHOULD));

        assertEquals(2, shouldClauses.size(), "Should have 2 SHOULD clauses (2 phrases)");

        // Both should be expanded to BooleanQuery
        assertInstanceOf(BooleanQuery.class, shouldClauses.get(0), "First phrase should be expanded");
        assertInstanceOf(BooleanQuery.class, shouldClauses.get(1), "Second phrase should be expanded");
    }
}
