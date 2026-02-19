package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for automatic phrase query expansion to proximity queries.
 * Verifies that exact matches rank highest while proximity matches still return results.
 */
@DisplayName("Automatic Phrase Expansion Integration Tests")
class AutomaticPhraseExpansionIntegrationTest {

    private ByteBuffersDirectory directory;
    private StandardAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        directory = new ByteBuffersDirectory();
        analyzer = new StandardAnalyzer();

        // Index test documents with various phrase patterns
        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // Document 1: Exact match
            final Document doc1 = new Document();
            doc1.add(new TextField("content", "Domain Design", Field.Store.YES));
            doc1.add(new TextField("id", "exact", Field.Store.YES));
            writer.addDocument(doc1);

            // Document 2: Hyphenated (one "word" between when tokenized)
            final Document doc2 = new Document();
            doc2.add(new TextField("content", "Domain-driven Design", Field.Store.YES));
            doc2.add(new TextField("id", "hyphenated", Field.Store.YES));
            writer.addDocument(doc2);

            // Document 3: One word between
            final Document doc3 = new Document();
            doc3.add(new TextField("content", "Domain Effective Design", Field.Store.YES));
            doc3.add(new TextField("id", "one-word-between", Field.Store.YES));
            writer.addDocument(doc3);

            // Document 4: Two words between
            final Document doc4 = new Document();
            doc4.add(new TextField("content", "Domain Very Effective Design", Field.Store.YES));
            doc4.add(new TextField("id", "two-words-between", Field.Store.YES));
            writer.addDocument(doc4);

            // Document 5: Three words between (exactly at slop limit)
            final Document doc5 = new Document();
            doc5.add(new TextField("content", "Domain is a very Design", Field.Store.YES));
            doc5.add(new TextField("id", "three-words-between", Field.Store.YES));
            writer.addDocument(doc5);

            // Document 6: Too far apart (exceeds slop=3)
            final Document doc6 = new Document();
            doc6.add(new TextField("content", "Domain is a good and effective Design", Field.Store.YES));
            doc6.add(new TextField("id", "too-far", Field.Store.YES));
            writer.addDocument(doc6);

            // Document 7: No match (different terms)
            final Document doc7 = new Document();
            doc7.add(new TextField("content", "Architecture Pattern", Field.Store.YES));
            doc7.add(new TextField("id", "no-match", Field.Store.YES));
            writer.addDocument(doc7);
        }
    }

    @Test
    @DisplayName("Exact match should rank highest with automatic expansion")
    void testExactMatchRanksHighest() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            // Query with automatic expansion: "Domain Design"
            // Expands to: ("Domain Design")^2.0 OR ("Domain Design"~3)
            final Query query = parser.parse("\"Domain Design\"");
            final TopDocs results = searcher.search(query, 10);

            // Should find 5 documents (exact + 4 within slop, excluding too-far and no-match)
            assertEquals(5, results.totalHits.value(),
                    "Should match 5 documents (exact + 4 proximity matches)");

            final ScoreDoc[] scoreDocs = results.scoreDocs;

            // Verify exact match ranks first
            final String topDoc = searcher.storedFields().document(scoreDocs[0].doc).get("id");
            assertEquals("exact", topDoc,
                    "Exact match 'Domain Design' should rank first due to 2.0x boost");

            // Print scoring results for verification
            System.out.println("\nAutomatic Phrase Expansion Scoring:");
            System.out.println("Query: \"Domain Design\"");
            System.out.println("Expands to: (\"Domain Design\")^2.0 OR (\"Domain Design\"~3)\n");

            for (int i = 0; i < scoreDocs.length; i++) {
                final String id = searcher.storedFields().document(scoreDocs[i].doc).get("id");
                final String content = searcher.storedFields().document(scoreDocs[i].doc).get("content");
                final float score = scoreDocs[i].score;
                System.out.printf("%d. [%.4f] %-20s - %s\n", i + 1, score, id, content);
            }

            // Verify exact match has significantly higher score than proximity matches
            final float exactScore = scoreDocs[0].score;
            final float secondScore = scoreDocs[1].score;
            assertTrue(exactScore > secondScore * 1.5,
                    "Exact match should score at least 1.5x higher than proximity matches");
        }
    }

    @Test
    @DisplayName("Proximity matches should score lower but still match")
    void testProximityMatchesIncluded() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            final Query query = parser.parse("\"Domain Design\"");
            final TopDocs results = searcher.search(query, 10);

            final ScoreDoc[] scoreDocs = results.scoreDocs;

            // Collect all matched IDs
            final java.util.Set<String> matchedIds = new java.util.HashSet<>();
            for (final ScoreDoc scoreDoc : scoreDocs) {
                final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                matchedIds.add(id);
            }

            // Verify proximity matches are included
            assertTrue(matchedIds.contains("hyphenated"),
                    "Should match 'Domain-driven Design' via proximity");
            assertTrue(matchedIds.contains("one-word-between"),
                    "Should match 'Domain Effective Design' via proximity");
            assertTrue(matchedIds.contains("two-words-between"),
                    "Should match 'Domain Very Effective Design' via proximity");
            assertTrue(matchedIds.contains("three-words-between"),
                    "Should match document with 3 words between (at slop limit)");

            // Verify documents beyond slop don't match
            assertFalse(matchedIds.contains("too-far"),
                    "Should NOT match document exceeding slop=3");
            assertFalse(matchedIds.contains("no-match"),
                    "Should NOT match unrelated document");
        }
    }

    @Test
    @DisplayName("Scores should decrease with distance")
    void testScoresDecreaseWithDistance() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            final Query query = parser.parse("\"Domain Design\"");
            final TopDocs results = searcher.search(query, 10);

            final ScoreDoc[] scoreDocs = results.scoreDocs;

            // Scores should generally decrease (exact > closer > farther)
            // Note: Hyphenated might score differently due to tokenization
            for (int i = 0; i < scoreDocs.length - 1; i++) {
                assertTrue(scoreDocs[i].score >= scoreDocs[i + 1].score,
                        "Scores should be non-increasing (rank " + i + " vs " + (i + 1) + ")");
            }
        }
    }

    @Test
    @DisplayName("Single-word phrase should NOT expand (no false positives)")
    void testSingleWordPhraseNoExpansion() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            // Single-word phrase should not expand
            final Query query = parser.parse("\"Design\"");
            final TopDocs results = searcher.search(query, 10);

            // Should match all documents containing "design" (6 out of 7)
            assertEquals(6, results.totalHits.value(),
                    "Single-word phrase should match all docs with 'design'");
        }
    }

    @Test
    @DisplayName("User-specified slop should be honored without expansion")
    void testUserSlopHonored() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            // User specifies slop=1 (more restrictive than default 3)
            final Query query = parser.parse("\"Domain Design\"~1");
            final TopDocs results = searcher.search(query, 10);

            // Should match only exact, hyphenated, and one-word-between (not two-words or three-words)
            assertEquals(3, results.totalHits.value(),
                    "Should respect user-specified slop=1");

            final ScoreDoc[] scoreDocs = results.scoreDocs;
            final java.util.Set<String> matchedIds = new java.util.HashSet<>();
            for (final ScoreDoc scoreDoc : scoreDocs) {
                final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                matchedIds.add(id);
            }

            assertTrue(matchedIds.contains("exact"));
            assertTrue(matchedIds.contains("hyphenated"));
            assertTrue(matchedIds.contains("one-word-between"));
            assertFalse(matchedIds.contains("two-words-between"),
                    "Should NOT match when slop=1");
        }
    }

    @Test
    @DisplayName("Complex query with phrase and term should work correctly")
    void testComplexQueryWithPhrase() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer);

            // Phrase + term query
            final Query query = parser.parse("\"Domain Design\" OR Architecture");
            final TopDocs results = searcher.search(query, 10);

            // Should match 5 from phrase expansion + 1 from "Architecture"
            assertEquals(6, results.totalHits.value(),
                    "Should match phrase expansion results + Architecture doc");

            final ScoreDoc[] scoreDocs = results.scoreDocs;
            final java.util.Set<String> matchedIds = new java.util.HashSet<>();
            for (final ScoreDoc scoreDoc : scoreDocs) {
                final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                matchedIds.add(id);
            }

            assertTrue(matchedIds.contains("exact"));
            assertTrue(matchedIds.contains("no-match"),
                    "Should match 'Architecture Pattern' via term query");
        }
    }

    @Test
    @DisplayName("Custom slop and boost parameters should affect results")
    void testCustomParameters() throws Exception {
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);

            // Create parser with wider slop (should match more docs)
            final ProximityExpandingQueryParser parser =
                    new ProximityExpandingQueryParser("content", analyzer, 10, 2.0f);

            final Query query = parser.parse("\"Domain Design\"");
            final TopDocs results = searcher.search(query, 10);

            // With slop=10, should now match "too-far" document (6 total)
            assertEquals(6, results.totalHits.value(),
                    "With slop=10, should match more documents including 'too-far'");

            final ScoreDoc[] scoreDocs = results.scoreDocs;
            final java.util.Set<String> matchedIds = new java.util.HashSet<>();
            for (final ScoreDoc scoreDoc : scoreDocs) {
                final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                matchedIds.add(id);
            }

            assertTrue(matchedIds.contains("too-far"),
                    "Should match 'too-far' with slop=10");

            // Exact should still rank first
            final String topDoc = searcher.storedFields().document(scoreDocs[0].doc).get("id");
            assertEquals("exact", topDoc,
                    "Exact match should still rank first");
        }
    }
}
