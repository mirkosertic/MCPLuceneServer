package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService.QueryMode;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService.SearchResult;
import de.mirkosertic.mcp.luceneserver.index.SemanticSearchResult;
import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests that verify {@code semanticSearch} document grouping, passage structure,
 * passage metadata, and pagination behaviour.
 */
@DisplayName("semanticSearch document grouping and passage tests")
class SemanticSearchDocumentGroupingTest {

    private static final int DIM = 768;

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private DocumentIndexer documentIndexer;
    private ONNXService onnxService;

    /**
     * Returns a unit vector where all values are equal and L2-normalised.
     * All components = 1/sqrt(dim).
     */
    private static float[] makeVectorA(final int dim) {
        final float[] v = new float[dim];
        final float val = (float) (1.0 / Math.sqrt(dim));
        java.util.Arrays.fill(v, val);
        return v;
    }

    /**
     * Returns a unit vector along the first axis: [1, 0, 0, ..., 0].
     * Cosine similarity with makeVectorA is 1/sqrt(dim) ≈ 0.036 for dim=768.
     */
    private static float[] makeVectorB(final int dim) {
        final float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    @BeforeEach
    void setUp() throws Exception {
        indexDir = tempDir.resolve("index");
        docsDir  = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);

        final ApplicationConfig config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(50L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(300);
        when(config.isExtractMetadata()).thenReturn(false);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        onnxService = mock(ONNXService.class);
        when(onnxService.getHiddenSize()).thenReturn(DIM);

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

    private void indexFileWithContent(final Path file, final String content) throws IOException {
        indexFileWithLanguage(file, content, "en");
    }

    private void indexFileWithLanguage(final Path file, final String content,
                                       final String language) throws IOException {
        final ExtractedDocument extracted = new ExtractedDocument(
                content, null, language, "text/plain", content.length());
        final Document parentDoc = documentIndexer.createDocument(file, extracted);
        indexService.indexDocument(parentDoc, content);
        indexService.commit();
        indexService.refreshSearcher();
    }

    // ========== Tests ==========

    @Test
    @DisplayName("single chunk document returns exactly one passage")
    void testSingleChunkParentHasOnePassage() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("single.txt", "This is a single chunk document.");
        indexFileWithContent(file, "This is a single chunk document.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).passages()).hasSize(1);
    }

    @Test
    @DisplayName("document with two chunks returns two passages under the same parent")
    void testMultipleChunksPerParentAllAppearAsPassages() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("twochunks.txt", "Document with two chunks of content.");
        indexFileWithContent(file, "Document with two chunks of content.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).passages()).hasSize(2);
    }

    @Test
    @DisplayName("passages are sorted by cosine score descending")
    void testPassagesAreSortedByCosineDescending() throws Exception {
        // vectorA as query: cosine(vectorA, vectorA)=1.0, cosine(vectorA, vectorB)≈0.036
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("sorted.txt", "Content for sorting test.");
        indexFileWithContent(file, "Content for sorting test.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final List<Passage> passages = result.documents().get(0).passages();
        assertThat(passages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(passages.get(0).score())
                .as("First passage should have score >= second passage score")
                .isGreaterThanOrEqualTo(passages.get(1).score());
    }

    @Test
    @DisplayName("passage chunkIndex values are 0 and 1 for a two-chunk document")
    void testPassageChunkIndexMatchesStoredIndex() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("chunkidx.txt", "Testing chunk index assignment.");
        indexFileWithContent(file, "Testing chunk index assignment.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final List<Passage> passages = result.documents().get(0).passages();
        final List<Integer> chunkIndices = passages.stream()
                .map(Passage::chunkIndex)
                .sorted()
                .toList();

        assertThat(chunkIndices).containsExactly(0, 1);
    }

    @Test
    @DisplayName("passage score is in cosine range [0.0, 1.0]")
    void testPassageScoreIsInCosineRange() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("scorerange.txt", "Document to test score range.");
        indexFileWithContent(file, "Document to test score range.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final double score = result.documents().get(0).passages().get(0).score();
        assertThat(score)
                .as("Passage score should be in [0.0, 1.0]")
                .isGreaterThanOrEqualTo(0.0)
                .isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("all semantic passages have source='semantic'")
    void testPassageSourceIsSemantic() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("source.txt", "Document to test passage source field.");
        indexFileWithContent(file, "Document to test passage source field.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final List<Passage> passages = result.documents().get(0).passages();
        assertThat(passages).isNotEmpty();
        assertThat(passages).allMatch(p -> "semantic".equals(p.source()));
    }

    @Test
    @DisplayName("single-chunk passage has position 0.0")
    void testPassagePositionSingleChunkIsZero() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("pos0.txt", "Single chunk position test.");
        indexFileWithContent(file, "Single chunk position test.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final double position = result.documents().get(0).passages().get(0).position();
        assertThat(position).isEqualTo(0.0);
    }

    @Test
    @DisplayName("first chunk has position 0.0 and second chunk has position 1.0")
    void testPassagePositionFirstAndLastChunk() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("positions.txt", "Document with two chunks for position test.");
        indexFileWithContent(file, "Document with two chunks for position test.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final List<Passage> passages = result.documents().get(0).passages();
        assertThat(passages).hasSizeGreaterThanOrEqualTo(2);

        final Passage chunk0 = passages.stream()
                .filter(p -> p.chunkIndex() != null && p.chunkIndex() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No passage with chunkIndex=0 found"));

        final Passage chunk1 = passages.stream()
                .filter(p -> p.chunkIndex() != null && p.chunkIndex() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No passage with chunkIndex=1 found"));

        assertThat(chunk0.position()).as("First chunk should have position 0.0").isEqualTo(0.0);
        assertThat(chunk1.position()).as("Second chunk should have position 1.0").isEqualTo(1.0);
    }

    @Test
    @DisplayName("search document metadata is populated correctly")
    void testSearchDocumentMetadataPopulated() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("metadata.txt", "Content for metadata test.");
        indexFileWithLanguage(file, "Content for metadata test.", "en");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final SearchDocument doc = result.documents().get(0);
        assertThat(doc.filePath())
                .as("filePath should not be null or blank")
                .isNotNull()
                .isNotBlank();
        assertThat(doc.fileType())
                .as("fileType should not be null or blank")
                .isNotNull()
                .isNotBlank();
        assertThat(doc.language())
                .as("language should be 'en'")
                .isEqualTo("en");
    }

    @Test
    @DisplayName("document score equals top passage score")
    void testSearchDocumentScoreEqualsTopPassageScore() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("docscore.txt", "Document to verify score equals top passage.");
        indexFileWithContent(file, "Document to verify score equals top passage.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        final SearchDocument doc = result.documents().get(0);
        assertThat(doc.score())
                .as("Document score should equal the score of the first (top) passage")
                .isEqualTo(doc.passages().get(0).score());
    }

    @Test
    @DisplayName("keyword search passages have null chunkIndex")
    void testKeywordPassageChunkIndexIsNull() throws Exception {
        // For the keyword search we still need the ONNX mock for indexing
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file = createTextFile("keyword.txt", "The quick brown fox jumps over the lazy dog.");
        indexFileWithContent(file, "The quick brown fox jumps over the lazy dog.");

        final SearchResult result = indexService.search(
                "quick", List.of(), 0, 10, "score", "desc", QueryMode.SIMPLE);

        assertThat(result.documents()).isNotEmpty();
        for (final SearchDocument doc : result.documents()) {
            assertThat(doc.passages()).allMatch(p -> p.chunkIndex() == null,
                    "keyword passages should have null chunkIndex");
        }
    }

    @Test
    @DisplayName("pagination over parents: two pages with pageSize=2 for three documents")
    void testPaginationOverParentsNotChunks() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file1 = createTextFile("page_doc1.txt", "First document for pagination test.");
        final Path file2 = createTextFile("page_doc2.txt", "Second document for pagination test.");
        final Path file3 = createTextFile("page_doc3.txt", "Third document for pagination test.");
        indexFileWithContent(file1, "First document for pagination test.");
        indexFileWithContent(file2, "Second document for pagination test.");
        indexFileWithContent(file3, "Third document for pagination test.");

        final SemanticSearchResult page0 = indexService.semanticSearch("query", null, 0, 2, 0.0f);
        assertThat(page0.documents())
                .as("Page 0 with pageSize=2 should return 2 documents")
                .hasSize(2);

        final SemanticSearchResult page1 = indexService.semanticSearch("query", null, 1, 2, 0.0f);
        assertThat(page1.documents())
                .as("Page 1 with pageSize=2 should return the remaining 1 document")
                .hasSize(1);

        assertThat(page0.totalHits())
                .as("Total hits should be 3 (distinct parents)")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("totalHits reflects distinct parent documents not individual chunks")
    void testTotalHitsReflectsDistinctParents() throws Exception {
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(makeVectorA(DIM), makeVectorB(DIM)));
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(makeVectorA(DIM));

        final Path file1 = createTextFile("total1.txt", "First document for total hits test.");
        final Path file2 = createTextFile("total2.txt", "Second document for total hits test.");
        indexFileWithContent(file1, "First document for total hits test.");
        indexFileWithContent(file2, "Second document for total hits test.");

        final SemanticSearchResult result = indexService.semanticSearch("query", null, 0, 10, 0.0f);

        assertThat(result.totalHits())
                .as("totalHits should be 2 (distinct parent documents), not 4 (chunks)")
                .isEqualTo(2);
    }
}
