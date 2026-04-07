package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryResponse;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@code profileQuery} after removal of vector search debug.
 *
 * <p>Verifies that {@code profileQuery} succeeds and returns a null {@code vectorSearchDebug}
 * field (since {@code buildVectorSearchDebug} has been removed), regardless of whether an
 * ONNX service is present.</p>
 */
@DisplayName("profileQuery integration tests (vector search debug removed)")
class VectorSearchDebugIntegrationTest {

    private static final int DIM = 64;

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path indexDirNoVector;
    private Path docsDir;

    private LuceneIndexService indexServiceWithVector;
    private LuceneIndexService indexServiceNoVector;
    private DocumentIndexer documentIndexer;
    private ONNXService onnxService;

    private static final String CONTENT_A =
            "Der Vertrag wurde gestern unterzeichnet. "
            + "Die Vertragsparteien sind sich einig ueber alle Konditionen.";

    private static float[] makeE1(final int dim) {
        final float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    private ApplicationConfig buildConfig(final Path path) {
        final ApplicationConfig cfg = mock(ApplicationConfig.class);
        when(cfg.getIndexPath()).thenReturn(path.toString());
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
        indexDir = tempDir.resolve("index-vector");
        indexDirNoVector = tempDir.resolve("index-no-vector");
        docsDir = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(indexDirNoVector);
        Files.createDirectories(docsDir);

        documentIndexer = new DocumentIndexer();

        // Set up ONNX mock
        onnxService = mock(ONNXService.class);
        when(onnxService.getHiddenSize()).thenReturn(DIM);
        when(onnxService.embed(anyString(), eq(ONNXService.QUERY_PREFIX)))
                .thenReturn(Arrays.copyOf(makeE1(DIM), DIM));
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(Arrays.copyOf(makeE1(DIM), DIM)));

        // Service with vector support
        indexServiceWithVector = new LuceneIndexService(buildConfig(indexDir), documentIndexer, onnxService);
        indexServiceWithVector.init();

        // Service without vector support (null onnxService)
        indexServiceNoVector = new LuceneIndexService(buildConfig(indexDirNoVector), new DocumentIndexer());
        indexServiceNoVector.init();

        // Index one document in both services
        final Path fileA = docsDir.resolve("doc_a.txt");
        Files.writeString(fileA, CONTENT_A);
        indexFile(indexServiceWithVector, fileA, CONTENT_A);

        final DocumentIndexer di2 = new DocumentIndexer();
        final ExtractedDocument ex = new ExtractedDocument(
                CONTENT_A, null, null, "text/plain", CONTENT_A.length());
        di2.indexDocument(di2.createDocument(fileA, ex), ex.content(), indexServiceNoVector);
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

    // ==================== Tests ====================

    @Test
    @DisplayName("profileQuery with onnxService present succeeds and returns null vectorSearchDebug")
    void testProfileQuerySucceedsWithVectorService() throws Exception {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(Map.of(
                "query", "Vertrag"
        ));

        final ProfileQueryResponse response = indexServiceWithVector.profileQuery(request);

        assertThat(response.success()).isTrue();
        assertThat(response.vectorSearchDebug())
                .as("vectorSearchDebug should be null — buildVectorSearchDebug has been removed")
                .isNull();
    }

    @Test
    @DisplayName("profileQuery without onnxService succeeds and returns null vectorSearchDebug")
    void testProfileQuerySucceedsWithoutVectorService() throws Exception {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(Map.of(
                "query", "Vertrag"
        ));

        final ProfileQueryResponse response = indexServiceNoVector.profileQuery(request);

        assertThat(response.success()).isTrue();
        assertThat(response.vectorSearchDebug())
                .as("vectorSearchDebug should be null — buildVectorSearchDebug has been removed")
                .isNull();
    }

    @Test
    @DisplayName("profileQuery with blank query succeeds")
    void testProfileQueryWithBlankQuery() throws Exception {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(Map.of());

        final ProfileQueryResponse response = indexServiceWithVector.profileQuery(request);

        assertThat(response.success()).isTrue();
        assertThat(response.vectorSearchDebug()).isNull();
    }

    @Test
    @DisplayName("profileQuery returns query analysis even without vector debug")
    void testProfileQueryReturnsQueryAnalysis() throws Exception {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(Map.of(
                "query", "Vertrag"
        ));

        final ProfileQueryResponse response = indexServiceWithVector.profileQuery(request);

        assertThat(response.success()).isTrue();
        assertThat(response.queryAnalysis())
                .as("queryAnalysis should be populated")
                .isNotNull();
    }
}
