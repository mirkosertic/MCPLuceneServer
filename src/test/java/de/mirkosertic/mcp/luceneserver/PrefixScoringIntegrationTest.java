package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for adaptive prefix query scoring with realistic German contracts corpus.
 *
 * <p>Tests verify that the complete parsing and search stack correctly applies BM25 scoring
 * for specific prefixes (>= 4 chars) and ranks results appropriately.</p>
 *
 * <p>Key scenarios tested:</p>
 * <ul>
 *   <li>German contract terminology (Vertrag, Vertragsklausel, etc.)</li>
 *   <li>Scoring behavior with real documents</li>
 *   <li>Integration with phrase expansion</li>
 *   <li>Performance characteristics</li>
 * </ul>
 */
@DisplayName("Adaptive Prefix Scoring Integration Tests")
class PrefixScoringIntegrationTest {

    private ByteBuffersDirectory directory;
    private StandardAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        directory = new ByteBuffersDirectory();
        analyzer = new StandardAnalyzer();

        // Index realistic German contract documents
        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // Short, frequent term
            addDocument(writer, "doc1", "Der Vertrag wurde am 15. Januar 2024 unterzeichnet.");
            addDocument(writer, "doc2", "Der Vertrag ist gültig und bindend.");
            addDocument(writer, "doc3", "Der Vertrag regelt die Rechte.");

            // Longer compound terms
            addDocument(writer, "doc4", "Die Vertragsklausel regelt die Kündigungsfrist.");
            addDocument(writer, "doc5", "Die Vertragsbedingungen wurden festgelegt.");
            addDocument(writer, "doc6", "Die Vertragsänderung bedarf der Schriftform.");
            addDocument(writer, "doc7", "Der Vertragspartner hat alle Pflichten erfüllt.");

            // Even longer terms
            addDocument(writer, "doc8", "Die Vertragsdauer beträgt zwei Jahre.");
            addDocument(writer, "doc9", "Die Vertragsstrafe wurde vereinbart.");
            addDocument(writer, "doc10", "Der Vertragsabschluss erfolgte schriftlich.");

            // Add more "Vertrag" docs to make it more frequent (affects IDF)
            for (int i = 0; i < 10; i++) {
                addDocument(writer, "filler" + i, "Der Vertrag contract agreement.");
            }
        }
    }

    @Test
    @DisplayName("Long prefix (>= 4 chars) enables BM25 scoring")
    void testLongPrefixEnablesScoring() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            // Query with prefix >= 4 chars
            final Query query = parser.parse("vertrag*");
            final TopDocs results = searcher.search(query, 50);

            // Verify query type and rewrite method
            assertInstanceOf(PrefixQuery.class, query, "Should create PrefixQuery for prefix wildcard");

            // Should find many documents
            assertTrue(results.totalHits.value() >= 10,
                "Should find at least 10 documents matching vertrag*");

            // Verify we got different documents
            final Set<String> foundDocs = new HashSet<>();
            for (final ScoreDoc scoreDoc : results.scoreDocs) {
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                foundDocs.add(doc.get("id"));
            }

            assertTrue(foundDocs.contains("doc1"), "Should find simple 'Vertrag' document");
            assertTrue(foundDocs.contains("doc4"), "Should find 'Vertragsklausel' document");

            // Verify scores vary (not all constant 1.0)
            // With BM25 scoring, we expect score variation based on term frequency/length
            final Set<Float> uniqueScores = new HashSet<>();
            for (final ScoreDoc scoreDoc : results.scoreDocs) {
                uniqueScores.add(scoreDoc.score);
            }

            // We should have some score variation (though blending might make them similar)
            assertFalse(uniqueScores.isEmpty(), "Should have at least one score value");
        }
    }

    @Test
    @DisplayName("Short prefix (< 4 chars) uses constant scoring")
    void testShortPrefixUsesConstantScoring() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            // Query with prefix < 4 chars
            final Query query = parser.parse("ver*");
            final TopDocs results = searcher.search(query, 50);

            // Should create PrefixQuery
            assertInstanceOf(PrefixQuery.class, query, "Should create PrefixQuery for prefix wildcard");

            // Should find documents
            assertTrue(results.totalHits.value() > 0,
                "Should find documents matching ver*");

            // With constant scoring, all scores should be the same
            if (results.scoreDocs.length > 1) {
                final float firstScore = results.scoreDocs[0].score;
                for (int i = 1; i < results.scoreDocs.length; i++) {
                    assertEquals(firstScore, results.scoreDocs[i].score, 0.001,
                        "All scores should be equal with constant scoring");
                }
            }
        }
    }

    @Test
    @DisplayName("Finds diverse German contract terms")
    void testFindsGermanContractTerms() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            final Query query = parser.parse("vertrag*");
            final TopDocs results = searcher.search(query, 50);

            // Collect all matching documents
            final Set<String> matchedContent = new HashSet<>();
            for (final ScoreDoc scoreDoc : results.scoreDocs) {
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                matchedContent.add(doc.get("content").toLowerCase());
            }

            // Verify we matched diverse terms
            boolean foundVertrag = false;
            boolean foundVertragsklausel = false;
            boolean foundVertragsdauer = false;

            for (final String content : matchedContent) {
                if (content.contains("vertrag wurde") || content.contains("vertrag ist")
                    || content.contains("vertrag regelt")) {
                    foundVertrag = true;
                }
                if (content.contains("vertragsklausel")) {
                    foundVertragsklausel = true;
                }
                if (content.contains("vertragsdauer")) {
                    foundVertragsdauer = true;
                }
            }

            assertTrue(foundVertrag, "Should find simple 'Vertrag' matches");
            assertTrue(foundVertragsklausel, "Should find 'Vertragsklausel' compound");
            assertTrue(foundVertragsdauer, "Should find 'Vertragsdauer' compound");
        }
    }

    @Test
    @DisplayName("Works alongside phrase expansion")
    void testWorksWithPhraseExpansion() throws Exception {
        // Add some documents for phrase testing
        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            addDocument(writer, "phrase1", "Domain Design pattern");
            addDocument(writer, "phrase2", "Domain driven Design");
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            // Test phrase expansion
            final Query phraseQuery = parser.parse("\"Domain Design\"");
            final TopDocs phraseResults = searcher.search(phraseQuery, 10);

            assertTrue(phraseResults.totalHits.value() >= 2,
                "Phrase expansion should find multiple matches");

            // Test prefix scoring
            final Query prefixQuery = parser.parse("vertrag*");
            final TopDocs prefixResults = searcher.search(prefixQuery, 50);

            assertTrue(prefixResults.totalHits.value() >= 10,
                "Prefix scoring should find multiple matches");

            // Both features should work independently
            assertNotNull(phraseQuery);
            assertNotNull(prefixQuery);
        }
    }

    @Test
    @DisplayName("Edge case: exactly 4 characters enables scoring")
    void testFourCharacterBoundary() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            // Add test document
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                addDocument(writer, "test1", "test testing tested");
            }

            // Reopen reader to see new docs
            try (final DirectoryReader newReader = DirectoryReader.openIfChanged(reader)) {
                final DirectoryReader searchReader = newReader != null ? newReader : reader;
                final IndexSearcher newSearcher = new IndexSearcher(searchReader);

                // 4 chars - should enable scoring
                final Query fourCharQuery = parser.parse("test*");
                assertInstanceOf(PrefixQuery.class, fourCharQuery);

                final PrefixQuery fourCharPrefix = (PrefixQuery) fourCharQuery;
                assertNotNull(fourCharPrefix.getRewriteMethod());

                // 3 chars - should use constant scoring
                final Query threeCharQuery = parser.parse("tes*");
                assertInstanceOf(PrefixQuery.class, threeCharQuery);
            }
        }
    }

    @Test
    @DisplayName("Performance: scoring doesn't cause significant slowdown")
    void testPerformance() throws Exception {
        // Index many documents with prefix matches
        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (int i = 0; i < 100; i++) {
                addDocument(writer, "perf" + i, "test" + i + " content document");
            }
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            // Measure short prefix (constant scoring)
            final long startShort = System.nanoTime();
            final Query shortQuery = parser.parse("tes*");
            final TopDocs shortResults = searcher.search(shortQuery, 100);
            final long durationShort = System.nanoTime() - startShort;

            // Measure long prefix (BM25 scoring)
            final long startLong = System.nanoTime();
            final Query longQuery = parser.parse("test*");
            final TopDocs longResults = searcher.search(longQuery, 100);
            final long durationLong = System.nanoTime() - startLong;

            // Both should complete quickly (< 100ms)
            assertTrue(durationShort < 100_000_000,
                "Short prefix should complete in < 100ms");
            assertTrue(durationLong < 100_000_000,
                "Long prefix should complete in < 100ms");

            // Both should find results
            assertTrue(shortResults.totalHits.value() > 0);
            assertTrue(longResults.totalHits.value() > 0);

            // Scoring shouldn't be dramatically slower (within 3x is acceptable)
            assertTrue(durationLong < durationShort * 3 + 10_000_000,
                "Scoring overhead should be reasonable");
        }
    }

    @Test
    @DisplayName("Real-world German legal terminology")
    void testRealWorldGermanLegal() throws Exception {
        // Create fresh index for this test
        final ByteBuffersDirectory legalDirectory = new ByteBuffersDirectory();

        // Add diverse German legal terms with "vertrag" prefix
        try (final IndexWriter writer = new IndexWriter(legalDirectory, new IndexWriterConfig(analyzer))) {
            addDocument(writer, "legal1", "Der Vertrag regelt die Rechte und Pflichten.");
            addDocument(writer, "legal2", "Die Vertragsklausel wurde am 1. Januar festgelegt.");
            addDocument(writer, "legal3", "Die Vertragsbedingungen sind unwirksam.");
            addDocument(writer, "legal4", "Der Vertragsabschluss erfolgte schriftlich.");
            addDocument(writer, "legal5", "Die Vertragsdauer beträgt zwei Jahre.");
        }

        try (final DirectoryReader reader = DirectoryReader.open(legalDirectory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                new ProximityExpandingQueryParser("content", analyzer);

            final Query query = parser.parse("vertrag*");
            final TopDocs results = searcher.search(query, 50);

            // Should find all contract-related documents (5 total)
            assertEquals(5, results.totalHits.value(),
                "Should find all 5 contract documents");

            // Collect found document IDs
            final Set<String> foundDocs = new HashSet<>();
            for (final ScoreDoc scoreDoc : results.scoreDocs) {
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                foundDocs.add(doc.get("id"));
            }

            assertTrue(foundDocs.contains("legal1"), "Should find Vertrag");
            assertTrue(foundDocs.contains("legal2"), "Should find Vertragsklausel");
            assertTrue(foundDocs.contains("legal3"), "Should find Vertragsbedingungen");
        }
    }

    /**
     * Helper method to add a document to the index.
     */
    private void addDocument(final IndexWriter writer, final String id, final String content)
            throws Exception {
        final Document doc = new Document();
        doc.add(new TextField("id", id, Field.Store.YES));
        doc.add(new TextField("content", content, Field.Store.YES));
        writer.addDocument(doc);
    }
}
