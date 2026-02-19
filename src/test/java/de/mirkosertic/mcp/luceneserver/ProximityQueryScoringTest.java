package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates that proximity queries automatically score exact matches highest,
 * with progressively lower scores for matches with more slop.
 */
class ProximityQueryScoringTest {

    @Test
    void proximityQueryScoresExactMatchHighest() throws Exception {
        // Create in-memory index with test documents
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // Document 1: Exact match
            final Document doc1 = new Document();
            doc1.add(new TextField("content", "Domain Design", Field.Store.YES));
            doc1.add(new TextField("id", "exact", Field.Store.YES));
            writer.addDocument(doc1);

            // Document 2: One word in between (hyphenated)
            final Document doc2 = new Document();
            doc2.add(new TextField("content", "Domain-driven Design", Field.Store.YES));
            doc2.add(new TextField("id", "hyphenated", Field.Store.YES));
            writer.addDocument(doc2);

            // Document 3: One word in between (space)
            final Document doc3 = new Document();
            doc3.add(new TextField("content", "Domain Effective Design", Field.Store.YES));
            doc3.add(new TextField("id", "one-word-between", Field.Store.YES));
            writer.addDocument(doc3);

            // Document 4: Two words in between
            final Document doc4 = new Document();
            doc4.add(new TextField("content", "Domain Very Effective Design", Field.Store.YES));
            doc4.add(new TextField("id", "two-words-between", Field.Store.YES));
            writer.addDocument(doc4);

            // Document 5: Too far apart (exceeds slop)
            final Document doc5 = new Document();
            doc5.add(new TextField("content", "Domain is a very good Design principle", Field.Store.YES));
            doc5.add(new TextField("id", "too-far", Field.Store.YES));
            writer.addDocument(doc5);
        }

        // Search with proximity query
        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final QueryParser parser = new QueryParser("content", analyzer);

            // Query: "Domain Design" with slop 2
            final Query query = parser.parse("\"Domain Design\"~2");
            final TopDocs results = searcher.search(query, 10);

            // Should find 4 documents (doc5 exceeds slop)
            assertEquals(4, results.totalHits.value(), "Should match 4 documents");

            // Verify scoring order: exact > one-word-between > two-words-between
            final ScoreDoc[] scoreDocs = results.scoreDocs;

            // Get document IDs by score order
            final String firstDoc = searcher.storedFields().document(scoreDocs[0].doc).get("id");
            final String secondDoc = searcher.storedFields().document(scoreDocs[1].doc).get("id");
            final String fourthDoc = searcher.storedFields().document(scoreDocs[3].doc).get("id");

            // Exact match should score highest
            assertEquals("exact", firstDoc,
                "Exact match 'Domain Design' should rank first");

            // One-word-between should score higher than two-words-between
            assertTrue(scoreDocs[1].score > scoreDocs[3].score,
                "One word between should score higher than two words between");

            // Document with slop=2 should rank last among matches
            assertEquals("two-words-between", fourthDoc,
                "Document with slop=2 should rank last");

            // Verify actual scores decrease with slop
            System.out.println("\nProximity Query Scoring Results:");
            System.out.println("Query: \"Domain Design\"~2\n");
            for (int i = 0; i < scoreDocs.length; i++) {
                final String id = searcher.storedFields().document(scoreDocs[i].doc).get("id");
                final String content = searcher.storedFields().document(scoreDocs[i].doc).get("content");
                final float score = scoreDocs[i].score;
                System.out.printf("%d. [%.4f] %-20s - %s\n", i + 1, score, id, content);
            }

            // Document 5 should NOT match (exceeds slop)
            for (final ScoreDoc scoreDoc : scoreDocs) {
                final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                assertNotEquals("too-far", id,
                    "Document exceeding slop=2 should not match");
            }
        }
    }

    @Test
    void exactMatchBoostingWithCombinedQuery() throws Exception {
        // Shows how to give exact matches an EXTRA boost beyond natural proximity scoring
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            final Document doc1 = new Document();
            doc1.add(new TextField("content", "Domain Design", Field.Store.YES));
            doc1.add(new TextField("id", "exact", Field.Store.YES));
            writer.addDocument(doc1);

            final Document doc2 = new Document();
            doc2.add(new TextField("content", "Domain-driven Design", Field.Store.YES));
            doc2.add(new TextField("id", "hyphenated", Field.Store.YES));
            writer.addDocument(doc2);
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final QueryParser parser = new QueryParser("content", analyzer);

            // Combined query: exact phrase boosted + proximity as fallback
            final Query query = parser.parse("(\"Domain Design\")^2.0 OR \"Domain Design\"~2");
            final TopDocs results = searcher.search(query, 10);

            final ScoreDoc[] scoreDocs = results.scoreDocs;
            final String firstDoc = searcher.storedFields().document(scoreDocs[0].doc).get("id");

            assertEquals("exact", firstDoc, "Exact match should rank first with boost");

            // The score gap should be larger than with proximity alone
            final float exactScore = scoreDocs[0].score;
            final float hyphenatedScore = scoreDocs[1].score;
            final float scoreRatio = exactScore / hyphenatedScore;

            System.out.println("\nBoosted Exact Match Results:");
            System.out.printf("Exact match score: %.4f\n", exactScore);
            System.out.printf("Hyphenated match score: %.4f\n", hyphenatedScore);
            System.out.printf("Score ratio: %.2fx\n", scoreRatio);

            assertTrue(scoreRatio > 1.5,
                "Exact match should score significantly higher with boost");
        }
    }
}
