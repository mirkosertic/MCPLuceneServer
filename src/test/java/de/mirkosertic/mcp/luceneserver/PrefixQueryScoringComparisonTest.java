package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates different scoring approaches for prefix queries and their impact.
 */
class PrefixQueryScoringComparisonTest {

    @Test
    void constantScoreVsScoringBoolean() throws Exception {
        // Create test index
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // Document 1: Short match, frequent
            addDocument(writer, "doc1", "vertrag vertrag vertrag");

            // Document 2: Medium match, less frequent
            addDocument(writer, "doc2", "vertragsklausel");

            // Document 3: Long match, rare
            addDocument(writer, "doc3", "vertragsbedingungen");

            // Document 4: Very long match, rare
            addDocument(writer, "doc4", "vertragsänderungsklausel");

            // Add more docs to make "vertrag" more common (affects IDF)
            for (int i = 0; i < 5; i++) {
                addDocument(writer, "filler" + i, "vertrag contract agreement");
            }
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);

            System.out.println("\n=== Prefix Query Scoring Comparison ===\n");

            // Test 1: Constant Score (default)
            System.out.println("1. CONSTANT_SCORE (default behavior):");
            testPrefixQuery(searcher, MultiTermQuery.CONSTANT_SCORE_REWRITE);

            // Test 2: Scoring Boolean Rewrite
            System.out.println("\n2. SCORING_BOOLEAN_REWRITE:");
            testPrefixQuery(searcher, MultiTermQuery.SCORING_BOOLEAN_REWRITE);

            // Test 3: Top Terms with Scoring
            System.out.println("\n3. TopTermsScoringBooleanQueryRewrite (Top 10):");
            testPrefixQuery(searcher, new MultiTermQuery.TopTermsScoringBooleanQueryRewrite(10));

            // Test 4: Blended Freq Scoring (Best for production)
            System.out.println("\n4. TopTermsBlendedFreqScoringRewrite (Top 10):");
            testPrefixQuery(searcher, new MultiTermQuery.TopTermsBlendedFreqScoringRewrite(10));
        }
    }

    @Test
    void demonstrateTermLengthBias() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // All documents mention the term once - only difference is term length
            addDocument(writer, "short", "vertrag");
            addDocument(writer, "medium", "vertragsklausel");
            addDocument(writer, "long", "vertragsbedingungen");
            addDocument(writer, "verylong", "vertragsänderungsvereinbarung");
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);

            System.out.println("\n=== Term Length Impact on Scoring ===\n");

            final PrefixQuery query = new PrefixQuery(new Term("content", "vertrag"));

            final TopDocs results = searcher.search(query, 10);

            System.out.println("Query: vertrag*");
            System.out.println("All docs have TF=1, only term length differs\n");

            for (int i = 0; i < results.scoreDocs.length; i++) {
                final ScoreDoc scoreDoc = results.scoreDocs[i];
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                final String id = doc.get("id");
                final String content = doc.get("content");

                System.out.printf("%d. [%.4f] %-10s - %s (%d chars)\n",
                    i + 1, scoreDoc.score, id, content, content.length());
            }

            // Verify shorter terms don't necessarily score higher
            // (IDF differences can outweigh term length)
            assertTrue(results.scoreDocs.length > 0);
        }
    }

    @Test
    void prefixLengthAffectsExpansion() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            // Add many "ver*" terms
            addDocument(writer, "1", "ver verarbeiten verändern veranlassen");
            addDocument(writer, "2", "verbinden verbieten verbreiten");
            addDocument(writer, "3", "verdienen vereinfachen verfügen");
            addDocument(writer, "4", "vergessen vergleichen verhalten");
            addDocument(writer, "5", "verkaufen verlassen vermeiden");

            // Add fewer "vertrag*" terms
            addDocument(writer, "6", "vertrag vertrags vertragsklausel");
        }

        try (final DirectoryReader reader = DirectoryReader.open(directory)) {
            final IndexSearcher searcher = new IndexSearcher(reader);

            System.out.println("\n=== Prefix Length Impact ===\n");

            // Short prefix - many matches
            countPrefixMatches(searcher, "ver", reader);

            // Medium prefix - fewer matches
            countPrefixMatches(searcher, "verf", reader);

            // Long prefix - very specific
            countPrefixMatches(searcher, "vertrag", reader);
        }
    }

    private void testPrefixQuery(final IndexSearcher searcher, final MultiTermQuery.RewriteMethod rewriteMethod)
            throws Exception {
        final PrefixQuery query = new PrefixQuery(new Term("content", "vertrag"), rewriteMethod);

        final TopDocs results = searcher.search(query, 10);

        System.out.printf("Query: vertrag* (%s)\n", rewriteMethod.getClass().getSimpleName());
        System.out.printf("Total hits: %d\n\n", results.totalHits.value());

        for (int i = 0; i < Math.min(4, results.scoreDocs.length); i++) {
            final ScoreDoc scoreDoc = results.scoreDocs[i];
            final Document doc = searcher.storedFields().document(scoreDoc.doc);
            final String id = doc.get("id");
            final String content = doc.get("content");

            System.out.printf("  %d. [%.4f] %-10s - %s\n",
                i + 1, scoreDoc.score, id, content);
        }
    }

    private void countPrefixMatches(final IndexSearcher searcher, final String prefix, final DirectoryReader reader)
            throws Exception {
        final PrefixQuery query = new PrefixQuery(new Term("content", prefix), MultiTermQuery.SCORING_BOOLEAN_REWRITE);

        final TopDocs results = searcher.search(query, 100);

        // Count unique terms that matched
        final int uniqueTerms = countMatchingTerms(prefix, reader);

        System.out.printf("Prefix: \"%s*\" - Matches %d unique terms, %d docs\n",
            prefix, uniqueTerms, results.totalHits.value());
    }

    private int countMatchingTerms(final String prefix, final DirectoryReader reader) throws Exception {
        int count = 0;
        for (final var leafReaderContext : reader.leaves()) {
            final var terms = leafReaderContext.reader().terms("content");
            if (terms != null) {
                final var termsEnum = terms.iterator();
                termsEnum.seekCeil(new org.apache.lucene.util.BytesRef(prefix));

                while (termsEnum.next() != null) {
                    final String term = termsEnum.term().utf8ToString();
                    if (!term.startsWith(prefix)) {
                        break;
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private void addDocument(final IndexWriter writer, final String id, final String content) throws Exception {
        final Document doc = new Document();
        doc.add(new TextField("id", id, Field.Store.YES));
        doc.add(new TextField("content", content, Field.Store.YES));
        writer.addDocument(doc);
    }
}
