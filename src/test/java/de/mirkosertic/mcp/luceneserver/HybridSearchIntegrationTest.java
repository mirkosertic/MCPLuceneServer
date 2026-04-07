package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import de.mirkosertic.mcp.luceneserver.onnx.ONNXService;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for BM25 text search with and without a vector-capable index service.
 *
 * <p>Hybrid RRF merging has been removed. These tests verify that BM25 text search works
 * correctly whether or not an ONNX service is present, and that passages are generated
 * appropriately for keyword matches.</p>
 */
@DisplayName("BM25 search integration tests (formerly hybrid search)")
class HybridSearchIntegrationTest {

    /** Unit vector dimension must match mocked hiddenSize. */
    private static final int DIM = 64;

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;

    private LuceneIndexService indexServiceWithVector;
    private LuceneIndexService indexServiceNoVector;

    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;
    private ONNXService onnxService;

    // Embeddings used only for index setup (not for RRF)
    private final float[] embeddingA = makeE1(DIM);
    private final float[] embeddingB = makeE1Neg(DIM);
    private final float[] embeddingC = makeE2(DIM);

    private Path fileA;
    private Path fileB;
    private Path fileC;

    private static final String CONTENT_A =
            "Der Vertrag wurde gestern unterzeichnet. "
            + "Die Vertragsparteien sind sich einig ueber alle Konditionen.";
    private static final String CONTENT_B =
            "Die Rechnung ist faellig. "
            + "Bitte begleichen Sie die offene Rechnung bis zum Monatsende.";
    private static final String CONTENT_C =
            "Die Zahlung ist eingegangen. "
            + "Die Zahlung erfolgte per Ueberweisung.";

    // ========== Vector factory helpers ==========

    /** L2-normalised unit vector with all energy in dimension 0. */
    private static float[] makeE1(final int dim) {
        final float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    /** Negated e1. */
    private static float[] makeE1Neg(final int dim) {
        final float[] v = new float[dim];
        v[0] = -1.0f;
        return v;
    }

    /** Unit vector with energy in dimension 1 (orthogonal to e1). */
    private static float[] makeE2(final int dim) {
        final float[] v = new float[dim];
        v[1] = 1.0f;
        return v;
    }

    // ========== Setup helpers ==========

    private ApplicationConfig buildConfig(final Path indexPath) {
        final ApplicationConfig cfg = mock(ApplicationConfig.class);
        when(cfg.getIndexPath()).thenReturn(indexPath.toString());
        when(cfg.getNrtRefreshIntervalMs()).thenReturn(50L);
        when(cfg.getMaxPassages()).thenReturn(3);
        when(cfg.getMaxPassageCharLength()).thenReturn(400);
        when(cfg.isExtractMetadata()).thenReturn(false);
        when(cfg.isDetectLanguage()).thenReturn(false);
        when(cfg.getMaxContentLength()).thenReturn(-1L);
        return cfg;
    }

    private void indexFile(final LuceneIndexService svc,
                           final Path file,
                           final String content) throws IOException {
        final ExtractedDocument extracted = new ExtractedDocument(
                content, null, null, "text/plain", content.length());
        final Document parentDoc = documentIndexer.createDocument(file, extracted);
        svc.indexDocument(parentDoc, content);
        svc.commit();
        svc.refreshSearcher();
    }

    @BeforeEach
    void setUp() throws Exception {
        indexDir = tempDir.resolve("index");
        docsDir  = tempDir.resolve("docs");
        final Path indexDirNoVector = tempDir.resolve("index-no-vector");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(indexDirNoVector);

        documentIndexer = new DocumentIndexer();

        // ---- ONNX mock ----
        onnxService = mock(ONNXService.class);
        when(onnxService.getHiddenSize()).thenReturn(DIM);

        // embedWithLateChunking: each document returns its own embedding vector
        when(onnxService.embedWithLateChunking(eq(CONTENT_A), anyString(), anyInt()))
                .thenReturn(List.of(Arrays.copyOf(embeddingA, DIM)));
        when(onnxService.embedWithLateChunking(eq(CONTENT_B), anyString(), anyInt()))
                .thenReturn(List.of(Arrays.copyOf(embeddingB, DIM)));
        when(onnxService.embedWithLateChunking(eq(CONTENT_C), anyString(), anyInt()))
                .thenReturn(List.of(Arrays.copyOf(embeddingC, DIM)));

        // ---- Services ----
        config = buildConfig(indexDir);
        indexServiceWithVector = new LuceneIndexService(config, documentIndexer, onnxService);
        indexServiceWithVector.init();

        final ApplicationConfig configNoVector = buildConfig(indexDirNoVector);
        indexServiceNoVector = new LuceneIndexService(configNoVector, new DocumentIndexer());
        indexServiceNoVector.init();

        // ---- Files ----
        fileA = docsDir.resolve("doc_a_vertrag.txt");
        fileB = docsDir.resolve("doc_b_rechnung.txt");
        fileC = docsDir.resolve("doc_c_zahlung.txt");
        Files.writeString(fileA, CONTENT_A);
        Files.writeString(fileB, CONTENT_B);
        Files.writeString(fileC, CONTENT_C);

        // Index all three documents in the vector-enabled service
        indexFile(indexServiceWithVector, fileA, CONTENT_A);
        indexFile(indexServiceWithVector, fileB, CONTENT_B);
        indexFile(indexServiceWithVector, fileC, CONTENT_C);

        // Also index in the no-vector service for text-only tests
        final DocumentIndexer di2 = new DocumentIndexer();
        for (final String[] pair : new String[][]{
                {fileA.toString(), CONTENT_A},
                {fileB.toString(), CONTENT_B},
                {fileC.toString(), CONTENT_C}}) {
            final Path p = Path.of(pair[0]);
            final ExtractedDocument ex = new ExtractedDocument(
                    pair[1], null, null, "text/plain", pair[1].length());
            di2.indexDocument(di2.createDocument(p, ex), ex.content(), indexServiceNoVector);
        }
        indexServiceNoVector.commit();
        indexServiceNoVector.refreshSearcher();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexServiceWithVector != null) {
            indexServiceWithVector.close();
        }
        if (indexServiceNoVector != null) {
            indexServiceNoVector.close();
        }
    }

    // ========== Tests ==========

    @Test
    @DisplayName("Text-only search (no onnxService) returns doc_a for 'Vertrag' query")
    void testTextOnlyMatchReturnsResult() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceNoVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result.totalHits())
                .as("Should find at least one result for 'Vertrag'")
                .isGreaterThanOrEqualTo(1);

        final List<String> filePaths = result.documents().stream()
                .map(SearchDocument::filePath)
                .toList();
        assertThat(filePaths)
                .as("doc_a should appear in text-only results for 'Vertrag'")
                .anyMatch(p -> p != null && p.contains("doc_a_vertrag"));
    }

    @Test
    @DisplayName("BM25 search with onnxService present returns doc_a for 'Vertrag' query")
    void testBM25SearchWithVectorServiceReturnsResult() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result.documents())
                .as("BM25 search should return at least one result")
                .isNotEmpty();

        final List<String> filePaths = result.documents().stream()
                .map(SearchDocument::filePath)
                .toList();
        assertThat(filePaths)
                .as("doc_a should appear in search results for 'Vertrag'")
                .anyMatch(p -> p != null && p.contains("doc_a_vertrag"));
    }

    @Test
    @DisplayName("vectorMatchInfo is null for all documents (RRF removed)")
    void testVectorMatchInfoAlwaysNull() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result.documents())
                .as("Search should return results")
                .isNotEmpty();

        for (final SearchDocument doc : result.documents()) {
            assertThat(doc.vectorMatchInfo())
                    .as("vectorMatchInfo should be null for all documents — hybrid RRF removed")
                    .isNull();
        }
    }

    @Test
    @DisplayName("vectorMatchInfo is null for doc_b (RRF removed)")
    void testDocsBelowCutoffNotInVectorResults() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Rechnung", List.of(), 0, 10, "_score", "desc");

        // Without hybrid RRF, vectorMatchInfo is always null
        for (final SearchDocument doc : result.documents()) {
            if (doc.filePath() != null && doc.filePath().contains("doc_b_rechnung")) {
                assertThat(doc.vectorMatchInfo())
                        .as("vectorMatchInfo should always be null — hybrid RRF has been removed")
                        .isNull();
            }
        }
    }

    @Test
    @DisplayName("Search with null onnxService works without NPE")
    void testNoOnnxCallWhenDisabled() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceNoVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result).isNotNull();
        assertThat(result.documents()).isNotNull();
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Passage source is 'keyword' when BM25 highlighter finds term matches in content")
    void testKeywordPassageSourceForTextMatch() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceNoVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        final java.util.Optional<SearchDocument> docA = result.documents().stream()
                .filter(d -> d.filePath() != null && d.filePath().contains("doc_a_vertrag"))
                .findFirst();

        assertThat(docA)
                .as("doc_a should appear in text-only results for 'Vertrag'")
                .isPresent();

        assertThat(docA.get().passages())
                .as("doc_a should have at least one passage")
                .isNotEmpty();

        assertThat(docA.get().passages().getFirst().source())
                .as("Passage source should be 'keyword' when BM25 highlighter found term matches")
                .isEqualTo("keyword");
    }

    @Test
    @DisplayName("Query 'contract' in English finds no RRF results in German-only corpus (no hybrid)")
    void testEnglishQueryNoHybridResults() throws Exception {
        // Without hybrid RRF, an English query against a German corpus relies solely on BM25
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("contract", List.of(), 0, 10, "_score", "desc");

        assertThat(result).isNotNull();
        assertThat(result.documents()).isNotNull();

        // All returned documents have null vectorMatchInfo
        for (final SearchDocument doc : result.documents()) {
            assertThat(doc.vectorMatchInfo())
                    .as("vectorMatchInfo should be null — hybrid RRF has been removed")
                    .isNull();
        }
    }
}
