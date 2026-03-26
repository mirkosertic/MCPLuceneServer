package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.onnx.ONNXService;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for vector-aware document indexing (parent/child block-join pattern).
 *
 * <p>Uses a real Lucene index with a mocked {@link ONNXService} that returns fixed
 * embeddings, allowing tests to verify the block-join parent/child document structure
 * without running an actual ONNX model.</p>
 */
@DisplayName("Vector indexing integration tests")
class VectorIndexingIntegrationTest {

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;
    private ONNXService onnxService;

    /** A normalized unit vector of dimension 768 (all equal, L2-normalized). */
    private static float[] makeUnitVector(final int dim) {
        final float[] v = new float[dim];
        final float val = (float) (1.0 / Math.sqrt(dim));
        java.util.Arrays.fill(v, val);
        return v;
    }

    @BeforeEach
    void setUp() throws Exception {
        indexDir = tempDir.resolve("index");
        docsDir  = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);

        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(50L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(300);
        when(config.isExtractMetadata()).thenReturn(false);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        // Mock ONNXService: hiddenSize = 768, returns 1 chunk per document
        onnxService = mock(ONNXService.class);
        when(onnxService.getHiddenSize()).thenReturn(768);
        final float[] embedding = makeUnitVector(768);
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(embedding));

        documentIndexer = new DocumentIndexer();
        indexService = new LuceneIndexService(config, documentIndexer, onnxService);
        indexService.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexService != null) {
            indexService.close();
        }
    }

    // ========== Helpers ==========

    private Path createTextFile(final String name, final String content) throws IOException {
        final Path file = docsDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private void indexFile(final Path file, final String content) throws IOException {
        final ExtractedDocument extracted = new ExtractedDocument(
                content, null, null, "text/plain", content.length());
        final Document parentDoc = documentIndexer.createDocument(file, extracted);
        indexService.indexDocument(file, parentDoc, content);
        indexService.commit();
        indexService.refreshSearcher();
    }

    /**
     * Count documents in the index that have a specific value for a given stored field.
     * Uses a raw DirectoryReader opened on the IndexWriter.
     */
    private int countDocsByFieldValue(final String field, final String value) throws IOException {
        try (final DirectoryReader reader = DirectoryReader.open(indexService.getIndexWriter())) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final TopDocs hits = searcher.search(new TermQuery(new Term(field, value)), Integer.MAX_VALUE);
            return (int) hits.totalHits.value();
        }
    }

    /** Return all stored documents as a list. */
    private List<Document> allDocuments() throws IOException {
        try (final DirectoryReader reader = DirectoryReader.open(indexService.getIndexWriter())) {
            final IndexSearcher searcher = new IndexSearcher(reader);
            final TopDocs hits = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            final List<Document> docs = new ArrayList<>();
            for (final ScoreDoc sd : hits.scoreDocs) {
                docs.add(searcher.storedFields().document(sd.doc));
            }
            return docs;
        }
    }

    // ========== Tests ==========

    @Test
    @DisplayName("Parent document has _doc_type='parent'")
    void testParentDocHasDocTypeParent() throws Exception {
        final Path file = createTextFile("parent_test.txt",
                "This is a document about contracts and legal matters.");
        indexFile(file, "This is a document about contracts and legal matters.");

        final int parentCount = countDocsByFieldValue(
                DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_PARENT);
        assertThat(parentCount)
                .as("Should have exactly one parent document")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Child documents have _doc_type='child'")
    void testChildDocsHaveDocTypeChild() throws Exception {
        // Mock returns a single embedding chunk per doc (set up in @BeforeEach)
        final Path file = createTextFile("child_test.txt",
                "Testing vector child documents with embedding.");
        indexFile(file, "Testing vector child documents with embedding.");

        final int childCount = countDocsByFieldValue(
                DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_CHILD);
        assertThat(childCount)
                .as("Should have at least one child document")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Indexing with 2-chunk mock produces 2 child docs")
    void testTwoChunksProduceTwoChildDocs() throws Exception {
        // Override the mock to return 2 embeddings
        final float[] emb1 = makeUnitVector(768);
        final float[] emb2 = makeUnitVector(768);
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(emb1, emb2));

        final Path file = createTextFile("two_chunks.txt",
                "First chunk content. Second chunk content. More text for the document.");
        indexFile(file, "First chunk content. Second chunk content. More text for the document.");

        final int childCount = countDocsByFieldValue(
                DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_CHILD);
        assertThat(childCount)
                .as("Should have exactly 2 child documents for 2-chunk mock")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("All documents (parent and children) have file_path field")
    void testFilePathOnAllDocs() throws Exception {
        final Path file = createTextFile("filepath_test.txt",
                "Checking that file_path is present on all index documents.");
        indexFile(file, "Checking that file_path is present on all index documents.");

        final List<Document> docs = allDocuments();
        assertThat(docs)
                .as("Should have indexed at least one document")
                .isNotEmpty();

        for (final Document doc : docs) {
            assertThat(doc.get("file_path"))
                    .as("Every document (parent or child) must have file_path set")
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Deleting by file_path removes both parent and children")
    void testAtomicDeletion() throws Exception {
        final Path file = createTextFile("delete_test.txt",
                "Document to be deleted along with its child chunks.");
        indexFile(file, "Document to be deleted along with its child chunks.");

        // Verify something was indexed
        final int beforeDeletion = (int) indexService.getDocumentCount();
        assertThat(beforeDeletion).isGreaterThan(0);

        // Delete using the documentIndexer's deleteDocument (uses Term on file_path)
        documentIndexer.deleteDocument(indexService.getIndexWriter(), file.toString());
        indexService.commit();
        indexService.refreshSearcher();

        // After deletion there should be no documents with this file_path
        final int parentAfter = countDocsByFieldValue("file_path", file.toString());
        assertThat(parentAfter)
                .as("All documents for the deleted file should be removed")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Embedding dimension mismatch triggers schemaUpgradeRequired")
    void testDimensionMismatchTriggersUpgrade() throws Exception {
        // Create index with hiddenSize=768
        final Path file = createTextFile("dim_test.txt", "Sample content for dimension test.");
        indexFile(file, "Sample content for dimension test.");
        indexService.close();
        indexService = null;

        // Re-open with a different hiddenSize (1024) → should trigger upgrade
        final ONNXService onnxService1024 = mock(ONNXService.class);
        when(onnxService1024.getHiddenSize()).thenReturn(1024);
        when(onnxService1024.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeUnitVector(1024)));

        final ApplicationConfig config2 = mock(ApplicationConfig.class);
        when(config2.getIndexPath()).thenReturn(indexDir.toString());
        when(config2.getNrtRefreshIntervalMs()).thenReturn(50L);
        when(config2.getMaxPassages()).thenReturn(3);
        when(config2.getMaxPassageCharLength()).thenReturn(300);
        when(config2.isExtractMetadata()).thenReturn(false);
        when(config2.isDetectLanguage()).thenReturn(false);
        when(config2.getMaxContentLength()).thenReturn(-1L);

        final LuceneIndexService service2 = new LuceneIndexService(config2, new DocumentIndexer(), onnxService1024);
        try {
            service2.init();
            assertThat(service2.isSchemaUpgradeRequired())
                    .as("Changing embedding dimension from 768 to 1024 should trigger schema upgrade")
                    .isTrue();
        } finally {
            service2.close();
        }
    }
}
