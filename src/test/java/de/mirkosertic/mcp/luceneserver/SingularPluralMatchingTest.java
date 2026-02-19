package de.mirkosertic.mcp.luceneserver;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests singular/plural matching behavior with OpenNLP lemmatization.
 *
 * <p>Critical test case: English technical terms in content
 * (e.g., "Recommendation Engine" vs. "Recommendation Engines")</p>
 */
@DisplayName("Singular/Plural Matching with Lemmatization")
class SingularPluralMatchingTest {

    @Test
    @DisplayName("English: singular in document matches plural in query")
    void englishSingularInDocumentMatchesPluralQuery() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        // Index with English lemmatizer
        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                final Document doc = new Document();
                doc.add(new TextField("content", "The recommendation engine provides good results.", Field.Store.YES));
                doc.add(new TextField("id", "singular", Field.Store.YES));
                writer.addDocument(doc);
            }

            // Search with plural
            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                final Query query = parser.parse("\"recommendation engines\"");
                final TopDocs results = searcher.search(query, 10);

                System.out.println("\n=== Test: Singular in Doc, Plural in Query (English) ===");
                System.out.println("Document: 'recommendation engine' (singular)");
                System.out.println("Query: 'recommendation engines' (plural)");
                System.out.println("Total hits: " + results.totalHits.value());

                if (results.totalHits.value() > 0) {
                    final String content = searcher.storedFields().document(results.scoreDocs[0].doc).get("content");
                    final float score = results.scoreDocs[0].score;
                    System.out.println("✅ MATCHED via lemmatization");
                    System.out.println("Score: " + score);
                    System.out.println("Content: " + content);
                } else {
                    System.out.println("❌ NO MATCH - Lemmatization not working!");
                }

                assertTrue(results.totalHits.value() > 0,
                    "Should match 'engine' (singular) when searching 'engines' (plural) via English lemmatization");
            }
        }
    }

    @Test
    @DisplayName("English: plural in document matches singular in query")
    void englishPluralInDocumentMatchesSingularQuery() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                final Document doc = new Document();
                doc.add(new TextField("content", "The recommendation engines provide good results.", Field.Store.YES));
                doc.add(new TextField("id", "plural", Field.Store.YES));
                writer.addDocument(doc);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                final Query query = parser.parse("\"recommendation engine\"");
                final TopDocs results = searcher.search(query, 10);

                System.out.println("\n=== Test: Plural in Doc, Singular in Query (English) ===");
                System.out.println("Document: 'recommendation engines' (plural)");
                System.out.println("Query: 'recommendation engine' (singular)");
                System.out.println("Total hits: " + results.totalHits.value());

                assertTrue(results.totalHits.value() > 0,
                    "Should match 'engines' (plural) when searching 'engine' (singular) via English lemmatization");
            }
        }
    }

    @Test
    @DisplayName("German: singular/plural matching (Vertrag/Verträge)")
    void germanSingularPluralMatching() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                // Document 1: singular
                final Document doc1 = new Document();
                doc1.add(new TextField("content", "Der Vertrag wurde heute unterzeichnet.", Field.Store.YES));
                doc1.add(new TextField("id", "singular", Field.Store.YES));
                writer.addDocument(doc1);

                // Document 2: plural
                final Document doc2 = new Document();
                doc2.add(new TextField("content", "Die Verträge wurden heute unterzeichnet.", Field.Store.YES));
                doc2.add(new TextField("id", "plural", Field.Store.YES));
                writer.addDocument(doc2);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                // Search for singular
                final Query singularQuery = parser.parse("Vertrag");
                final TopDocs singularResults = searcher.search(singularQuery, 10);

                System.out.println("\n=== Test: German Singular/Plural ===");
                System.out.println("Documents: 'Vertrag' (singular) + 'Verträge' (plural)");
                System.out.println("Query: 'Vertrag' (singular)");
                System.out.println("Total hits: " + singularResults.totalHits.value());

                assertEquals(2, singularResults.totalHits.value(),
                    "Should find both singular 'Vertrag' and plural 'Verträge' via German lemmatization");

                // Search for plural
                final Query pluralQuery = parser.parse("Verträge");
                final TopDocs pluralResults = searcher.search(pluralQuery, 10);

                System.out.println("Query: 'Verträge' (plural)");
                System.out.println("Total hits: " + pluralResults.totalHits.value());

                assertEquals(2, pluralResults.totalHits.value(),
                    "Should find both singular and plural via German lemmatization");
            }
        }
    }

    @Test
    @DisplayName("English: both singular and plural in separate docs")
    void englishSingularPluralBothDocs() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                final Document doc1 = new Document();
                doc1.add(new TextField("content", "The recommendation engine is an important tool.", Field.Store.YES));
                doc1.add(new TextField("id", "singular", Field.Store.YES));
                writer.addDocument(doc1);

                final Document doc2 = new Document();
                doc2.add(new TextField("content", "The recommendation engines are important tools.", Field.Store.YES));
                doc2.add(new TextField("id", "plural", Field.Store.YES));
                writer.addDocument(doc2);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                // Search for plural
                final Query pluralQuery = parser.parse("engines");
                final TopDocs pluralResults = searcher.search(pluralQuery, 10);

                System.out.println("\n=== Test: English Singular/Plural (Both Docs) ===");
                System.out.println("Documents: 'engine' (singular) + 'engines' (plural)");
                System.out.println("Query: 'engines' (plural)");
                System.out.println("Total hits: " + pluralResults.totalHits.value());

                assertEquals(2, pluralResults.totalHits.value(),
                    "Should find both 'engine' and 'engines' via English lemmatization");

                // Search for singular
                final Query singularQuery = parser.parse("engine");
                final TopDocs singularResults = searcher.search(singularQuery, 10);

                System.out.println("Query: 'engine' (singular)");
                System.out.println("Total hits: " + singularResults.totalHits.value());

                assertEquals(2, singularResults.totalHits.value(),
                    "Should find both 'engine' and 'engines' via English lemmatization");
            }
        }
    }

    @Test
    @DisplayName("Score difference: exact match vs lemmatized match")
    void demonstrateScoreDifference() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                // Document 1: exact match
                final Document doc1 = new Document();
                doc1.add(new TextField("content", "The recommendation engines are great.", Field.Store.YES));
                doc1.add(new TextField("id", "exact", Field.Store.YES));
                writer.addDocument(doc1);

                // Document 2: singular (lemmatized match)
                final Document doc2 = new Document();
                doc2.add(new TextField("content", "The recommendation engine is great.", Field.Store.YES));
                doc2.add(new TextField("id", "singular", Field.Store.YES));
                writer.addDocument(doc2);

                // Document 3: unrelated
                final Document doc3 = new Document();
                doc3.add(new TextField("content", "The search algorithm is great.", Field.Store.YES));
                doc3.add(new TextField("id", "unrelated", Field.Store.YES));
                writer.addDocument(doc3);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                final Query query = parser.parse("recommendation engines");
                final TopDocs results = searcher.search(query, 10);

                System.out.println("\n=== Test: Score Difference (Exact vs Lemmatized) ===");
                System.out.println("Query: 'recommendation engines' (plural)");
                System.out.println("Total hits: " + results.totalHits.value());

                for (int i = 0; i < results.scoreDocs.length; i++) {
                    final ScoreDoc scoreDoc = results.scoreDocs[i];
                    final String id = searcher.storedFields().document(scoreDoc.doc).get("id");
                    final float score = scoreDoc.score;
                    System.out.printf("%d. %-10s - Score: %.4f%n", i + 1, id, score);
                }

                assertTrue(results.totalHits.value() >= 2,
                    "Should find at least exact match and singular form");

                // Both should match due to lemmatization
                // Scores might be similar since both are lemmatized to the same form
            }
        }
    }

    @Test
    @DisplayName("Phrase query with singular/plural - critical case")
    void phraseQueryWithSingularPlural() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        try (final OpenNLPLemmatizingAnalyzer analyzer = new OpenNLPLemmatizingAnalyzer("en")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                final Document doc = new Document();
                doc.add(new TextField("content", "The recommendation engine provides good results.", Field.Store.YES));
                doc.add(new TextField("id", "singular-phrase", Field.Store.YES));
                writer.addDocument(doc);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", analyzer);

                // Phrase query with plural - CRITICAL CASE
                final Query query = parser.parse("\"recommendation engines\"");
                final TopDocs results = searcher.search(query, 10);

                System.out.println("\n=== Test: Phrase Query Singular/Plural ===");
                System.out.println("Document: 'recommendation engine' (singular, in phrase)");
                System.out.println("Query: '\"recommendation engines\"' (phrase with plural)");
                System.out.println("Total hits: " + results.totalHits.value());

                if (results.totalHits.value() > 0) {
                    System.out.println("✅ MATCHED - Lemmatization works in phrase queries");
                    System.out.println("Score: " + results.scoreDocs[0].score);
                } else {
                    System.out.println("⚠️  NO MATCH - This is expected behavior");
                    System.out.println("Lemmatization normalizes both to same form, so phrase matching works");
                }

                // This should work because lemmatization happens during both indexing and querying
                // Both "engine" and "engines" lemmatize to "engine", so the phrase should match
                assertTrue(results.totalHits.value() > 0,
                    "Should match because both singular and plural lemmatize to the same form");
            }
        }
    }

    @Test
    @DisplayName("Mixed language concern: English terms with German analyzer (isolated test)")
    void mixedLanguageEnglishWithGermanAnalyzer() throws Exception {
        final ByteBuffersDirectory directory = new ByteBuffersDirectory();

        // NOTE: This is an ISOLATED test using only the German analyzer.
        // In PRODUCTION, DocumentIndexer creates BOTH content_lemma_de AND content_lemma_en fields,
        // enabling mixed-language matching. This test demonstrates the limitation when using
        // a single-language analyzer in isolation (not representative of production behavior).
        // See SearchPrecisionRecallRegressionTest Category 14 for production dual-field tests.

        // Index with German analyzer (simulates German document with English technical terms)
        try (final OpenNLPLemmatizingAnalyzer germanAnalyzer = new OpenNLPLemmatizingAnalyzer("de")) {
            try (final IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(germanAnalyzer))) {
                final Document doc = new Document();
                // German text with English technical term
                doc.add(new TextField("content", "Die Recommendation Engine ist ein wichtiges Tool.", Field.Store.YES));
                doc.add(new TextField("id", "mixed-de", Field.Store.YES));
                writer.addDocument(doc);
            }

            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final IndexSearcher searcher = new IndexSearcher(reader);
                final ProximityExpandingQueryParser parser = new ProximityExpandingQueryParser("content", germanAnalyzer);

                // Search with plural form
                final Query query = parser.parse("\"Recommendation Engines\"");
                final TopDocs results = searcher.search(query, 10);

                System.out.println("\n=== Test: Mixed Language (English in German doc, German analyzer - ISOLATED) ===");
                System.out.println("Document: 'Recommendation Engine' (English term in German text)");
                System.out.println("Indexed with: ONLY German analyzer (not production dual-field setup)");
                System.out.println("Query: 'Recommendation Engines' (plural)");
                System.out.println("Total hits: " + results.totalHits.value());

                if (results.totalHits.value() > 0) {
                    System.out.println("✅ MATCHED");
                } else {
                    System.out.println("❌ NO MATCH - Expected in this isolated test (single analyzer)");
                    System.out.println("In PRODUCTION, DocumentIndexer creates BOTH language fields for ALL documents");
                }

                // In this isolated test with only a German analyzer, we expect NO match
                // In production with DocumentIndexer, this WOULD match via content_lemma_en field
                assertEquals(0, results.totalHits.value(),
                    "Isolated test with single German analyzer cannot match English plural/singular " +
                    "(in production, dual-field indexing via DocumentIndexer solves this)");
            }
        }
    }
}
