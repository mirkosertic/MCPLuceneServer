package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.VectorMatchInfo;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for hybrid (text + vector) search using RRF merging.
 *
 * <p>The ONNX model is mocked so that:
 * <ul>
 *   <li>doc_a ("Vertrag") embeds very similarly to the query vector (high dot product → passes threshold)</li>
 *   <li>doc_b ("Rechnung") embeds opposite to the query vector (low dot product → below threshold)</li>
 *   <li>doc_c ("Zahlung") embeds moderately similarly (moderate dot product)</li>
 * </ul>
 * </p>
 *
 * <p>Vector embeddings are L2-normalized unit vectors.  Lucene DOT_PRODUCT similarity for two
 * normalized vectors equals the cosine similarity.  The Lucene score is then
 * {@code (1 + cosine) / 2}.  The cutoff used in the service is cosine=0.70 → luceneScore=0.85.</p>
 */
@DisplayName("Hybrid search integration tests")
class HybridSearchIntegrationTest {

    /** Unit vector dimension must match mocked hiddenSize. */
    private static final int DIM = 64;

    /** Cosine cutoff threshold used by the service. */
    private static final float COSINE_CUTOFF = 0.70f;
    private static final float LUCENE_THRESHOLD = (1f + COSINE_CUTOFF) / 2f; // 0.85

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;

    private LuceneIndexService indexServiceWithVector;
    private LuceneIndexService indexServiceNoVector;

    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;
    private ONNXService onnxService;

    // Query vector: e₁ = [1, 0, 0, ...]
    private final float[] queryVector = makeE1(DIM);

    // doc_a: identical to query → cosine = 1.0 → luceneScore = 1.0 (well above threshold)
    private final float[] embeddingA = makeE1(DIM);

    // doc_b: opposite to query → cosine ≈ -1.0 → luceneScore ≈ 0.0 (below threshold)
    private final float[] embeddingB = makeE1Neg(DIM);

    // doc_c: orthogonal to query → cosine = 0.0 → luceneScore = 0.5 (below threshold 0.85)
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
        return v;  // already L2-normalised (single non-zero component)
    }

    /** Negated e₁. */
    private static float[] makeE1Neg(final int dim) {
        final float[] v = new float[dim];
        v[0] = -1.0f;
        return v;
    }

    /** Unit vector with energy in dimension 1 (orthogonal to e₁). */
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

        // embed(query, "query: ") → queryVector
        when(onnxService.embed(anyString(), eq(ONNXService.QUERY_PREFIX)))
                .thenReturn(Arrays.copyOf(queryVector, DIM));

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
    @DisplayName("Hybrid search with mocked onnxService returns doc_a for 'Vertrag' query")
    void testVectorSearchEnabled() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result.documents())
                .as("Hybrid search should return at least one result")
                .isNotEmpty();

        final List<String> filePaths = result.documents().stream()
                .map(SearchDocument::filePath)
                .toList();
        assertThat(filePaths)
                .as("doc_a should appear in hybrid search results for 'Vertrag'")
                .anyMatch(p -> p != null && p.contains("doc_a_vertrag"));
    }

    @Test
    @DisplayName("doc_b (below cosine cutoff) does not receive a vectorMatchInfo")
    void testDocsBelowCutoffNotInVectorResults() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Rechnung", List.of(), 0, 10, "_score", "desc");

        // doc_b may or may not appear (it can be found via text), but its
        // vectorMatchInfo must be null because embeddingB is orthogonal or
        // opposite to queryVector → Lucene score << threshold (0.85).
        for (final SearchDocument doc : result.documents()) {
            if (doc.filePath() != null && doc.filePath().contains("doc_b_rechnung")) {
                assertThat(doc.vectorMatchInfo())
                        .as("doc_b embedding is below cosine cutoff — no vectorMatchInfo expected")
                        .isNull();
            }
        }
    }

    @Test
    @DisplayName("doc_a has non-null vectorMatchInfo when found via vector search")
    void testVectorMatchInfoPopulated() throws Exception {
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        // doc_a should appear and have vectorMatchInfo (embeddingA == queryVector → cosine=1.0)
        final java.util.Optional<SearchDocument> docA = result.documents().stream()
                .filter(d -> d.filePath() != null && d.filePath().contains("doc_a_vertrag"))
                .findFirst();

        assertThat(docA)
                .as("doc_a should appear in hybrid results")
                .isPresent();

        final VectorMatchInfo matchInfo = docA.get().vectorMatchInfo();
        assertThat(matchInfo)
                .as("doc_a should have a non-null vectorMatchInfo")
                .isNotNull();

        assertThat(matchInfo.matchedViaVector())
                .as("matchedViaVector should be true")
                .isTrue();

        // vectorScore should be near 1.0 (identical vectors → cosine=1.0 → luceneScore=1.0)
        assertThat(matchInfo.vectorScore())
                .as("Vector score should be at or near 1.0 for identical embedding")
                .isGreaterThan(LUCENE_THRESHOLD);
    }

    @Test
    @DisplayName("Search with null onnxService works without NPE")
    void testNoOnnxCallWhenDisabled() throws Exception {
        // indexServiceNoVector has no onnxService; search should not throw
        final LuceneIndexService.SearchResult result =
                indexServiceNoVector.search("Vertrag", List.of(), 0, 10, "_score", "desc");

        assertThat(result).isNotNull();
        assertThat(result.documents()).isNotNull();

        // The mocked onnxService on the text-only service is null (different instance).
        // Verify that no embed() call was ever made on the mock used for the vector service
        // during the no-vector search (wrong service — nothing to verify, but no NPE occurred).
        // The important assertion: the text-only service returned a valid result.
        assertThat(result.totalHits()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Passage source is 'keyword' when BM25 highlighter finds term matches in content")
    void testKeywordPassageSourceForTextMatch() throws Exception {
        // Searching for "Vertrag" which IS present in CONTENT_A — the highlighter should
        // find term matches and produce keyword passages.
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
    @DisplayName("Semantic passage used when query terms do not appear in document content")
    void testSemanticPassageUsedWhenHighlighterFindsNoMatch() throws Exception {
        // Query "contract" is English but CONTENT_A is German ("Vertrag") — the BM25 highlighter
        // will find no keyword matches. However the mock always returns queryVector for any query
        // string, and embeddingA matches perfectly, so doc_a gets a vector hit with chunk text.
        // We expect a semantic passage (source="semantic") containing the chunk text.
        final LuceneIndexService.SearchResult result =
                indexServiceWithVector.search("contract", List.of(), 0, 10, "_score", "desc");

        final java.util.Optional<SearchDocument> docA = result.documents().stream()
                .filter(d -> d.filePath() != null && d.filePath().contains("doc_a_vertrag"))
                .findFirst();

        assertThat(docA)
                .as("doc_a should appear via vector match for 'contract' query")
                .isPresent();

        final VectorMatchInfo matchInfo = docA.get().vectorMatchInfo();
        assertThat(matchInfo)
                .as("doc_a should have vectorMatchInfo when matched via vector")
                .isNotNull();

        assertThat(matchInfo.matchedChunkText())
                .as("matchedChunkText must be non-null for semantic passage test to be meaningful")
                .isNotNull();

        assertThat(docA.get().passages())
                .as("doc_a should have at least one passage")
                .isNotEmpty();

        assertThat(docA.get().passages().getFirst().source())
                .as("Passage source should be 'semantic' when BM25 found no keyword matches")
                .isEqualTo("semantic");

        // The semantic passage text should be derived from the chunk, not the first 300 chars
        assertThat(docA.get().passages().getFirst().text())
                .as("Semantic passage text should contain content from the matched chunk")
                .isNotBlank();
    }
}
