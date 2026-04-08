package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.SemanticSearchResult;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
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
 * Integration tests that verify {@code semanticSearch} correctly applies user-supplied
 * filters as a <em>post-filter</em> on parent documents rather than as a KNN pre-filter
 * on child documents.
 *
 * <p>The core regression being tested: filter fields ({@code language}, {@code file_extension},
 * etc.) only exist on parent documents, <em>not</em> on child chunk documents. A pre-filter
 * would match zero child docs and always return empty results. The fix moves filter evaluation
 * to the parent-document lookup phase.</p>
 */
@DisplayName("semanticSearch post-filter integration tests")
class SemanticSearchFilterIntegrationTest {

    private static final int DIM = 768;

    @TempDir
    Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private DocumentIndexer documentIndexer;
    private ONNXService onnxService;

    /** Returns a unit vector where all values are equal and L2-normalised. */
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

        final ApplicationConfig config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(50L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(300);
        when(config.isExtractMetadata()).thenReturn(false);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        // Mock ONNXService: returns a single fixed-vector chunk and the same vector for embed()
        onnxService = mock(ONNXService.class);
        when(onnxService.getHiddenSize()).thenReturn(DIM);
        final float[] embedding = makeUnitVector(DIM);
        when(onnxService.embedWithLateChunking(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(embedding));
        // embed() is called for the query vector; return the same unit vector so all docs score equally
        when(onnxService.embed(anyString(), anyString()))
                .thenReturn(embedding);

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

    /**
     * Indexes a file with explicitly supplied language and file extension metadata.
     * The {@code detectedLanguage} parameter maps to the {@code language} field on the parent doc.
     */
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
    @DisplayName("semanticSearch without filters returns all matching parents")
    void testSemanticSearchNoFilterReturnsAll() throws Exception {
        final Path deFile = createTextFile("doc_de.txt", "Dieses Dokument ist auf Deutsch.");
        final Path enFile = createTextFile("doc_en.txt", "This document is in English.");
        indexFileWithLanguage(deFile, "Dieses Dokument ist auf Deutsch.", "de");
        indexFileWithLanguage(enFile, "This document is in English.", "en");

        final SemanticSearchResult result = indexService.semanticSearch(
                "some query", null, 0, 10, 0.0f);

        assertThat(result.searchResult().documents())
                .as("Without filters both documents should be returned")
                .hasSize(2);
    }

    @Test
    @DisplayName("semanticSearch with language=de filter returns only the German parent")
    void testSemanticSearchLanguageFilterReturnsOnlyMatchingParent() throws Exception {
        final Path deFile = createTextFile("german.txt", "Dieses Dokument ist auf Deutsch.");
        final Path enFile = createTextFile("english.txt", "This document is in English.");
        indexFileWithLanguage(deFile, "Dieses Dokument ist auf Deutsch.", "de");
        indexFileWithLanguage(enFile, "This document is in English.", "en");

        final SearchFilter langFilter = new SearchFilter("language", "eq", "de",
                null, null, null, null);
        final SemanticSearchResult result = indexService.semanticSearch(
                "some query", List.of(langFilter), 0, 10, 0.0f);

        assertThat(result.searchResult().documents())
                .as("With language=de filter, only the German document should be returned")
                .hasSize(1);

        // The passage text should contain content from the German document
        final String passageText = result.searchResult().documents().get(0).passages().get(0).text();
        assertThat(passageText)
                .as("The returned passage should be from the German document")
                .contains("Deutsch");
    }

    @Test
    @DisplayName("semanticSearch with language=en filter returns only the English parent")
    void testSemanticSearchLanguageFilterEnReturnsOnlyEnglishParent() throws Exception {
        final Path deFile = createTextFile("german2.txt", "Dieses Dokument ist auf Deutsch.");
        final Path enFile = createTextFile("english2.txt", "This document is in English.");
        indexFileWithLanguage(deFile, "Dieses Dokument ist auf Deutsch.", "de");
        indexFileWithLanguage(enFile, "This document is in English.", "en");

        final SearchFilter langFilter = new SearchFilter("language", "eq", "en",
                null, null, null, null);
        final SemanticSearchResult result = indexService.semanticSearch(
                "some query", List.of(langFilter), 0, 10, 0.0f);

        assertThat(result.searchResult().documents())
                .as("With language=en filter, only the English document should be returned")
                .hasSize(1);

        final String passageText = result.searchResult().documents().get(0).passages().get(0).text();
        assertThat(passageText)
                .as("The returned passage should be from the English document")
                .contains("English");
    }

    @Test
    @DisplayName("semanticSearch with language filter for non-existent language returns empty results")
    void testSemanticSearchLanguageFilterNoMatchReturnsEmpty() throws Exception {
        final Path deFile = createTextFile("german3.txt", "Dieses Dokument ist auf Deutsch.");
        indexFileWithLanguage(deFile, "Dieses Dokument ist auf Deutsch.", "de");

        final SearchFilter langFilter = new SearchFilter("language", "eq", "fr",
                null, null, null, null);
        final SemanticSearchResult result = indexService.semanticSearch(
                "some query", List.of(langFilter), 0, 10, 0.0f);

        assertThat(result.searchResult().documents())
                .as("Filter for non-existent language should return no results")
                .isEmpty();
    }

    @Test
    @DisplayName("semanticSearch with file_extension filter returns only matching parent")
    void testSemanticSearchFileExtensionFilter() throws Exception {
        final Path txtFile = createTextFile("document.txt", "A plain text document.");
        // Create a .md file (different extension)
        final Path mdFile = docsDir.resolve("readme.md");
        Files.writeString(mdFile, "A markdown document.");

        indexFileWithLanguage(txtFile, "A plain text document.", "en");
        indexFileWithLanguage(mdFile, "A markdown document.", "en");

        final SearchFilter extFilter = new SearchFilter("file_extension", "eq", "txt",
                null, null, null, null);
        final SemanticSearchResult result = indexService.semanticSearch(
                "some query", List.of(extFilter), 0, 10, 0.0f);

        assertThat(result.searchResult().documents())
                .as("With file_extension=txt filter, only the .txt document should be returned")
                .hasSize(1);

        final String passageText = result.searchResult().documents().get(0).passages().get(0).text();
        assertThat(passageText)
                .as("The returned passage should be from the .txt document")
                .contains("plain text");
    }

    @Test
    @DisplayName("semanticSearch with multiple documents and language filter respects page size")
    void testSemanticSearchFilterWithPagination() throws Exception {
        // Index 3 German and 2 English documents
        for (int i = 1; i <= 3; i++) {
            final Path f = createTextFile("de_" + i + ".txt", "Deutsches Dokument Nummer " + i + ".");
            indexFileWithLanguage(f, "Deutsches Dokument Nummer " + i + ".", "de");
        }
        for (int i = 1; i <= 2; i++) {
            final Path f = createTextFile("en_" + i + ".txt", "English document number " + i + ".");
            indexFileWithLanguage(f, "English document number " + i + ".", "en");
        }

        final SearchFilter langFilter = new SearchFilter("language", "eq", "de",
                null, null, null, null);

        // Page 0, size 2: should return 2 German documents
        final SemanticSearchResult page0 = indexService.semanticSearch(
                "some query", List.of(langFilter), 0, 2, 0.0f);
        assertThat(page0.searchResult().documents())
                .as("Page 0 with size 2 should return 2 German documents")
                .hasSize(2);

        // Page 1, size 2: should return the remaining 1 German document
        final SemanticSearchResult page1 = indexService.semanticSearch(
                "some query", List.of(langFilter), 1, 2, 0.0f);
        assertThat(page1.searchResult().documents())
                .as("Page 1 with size 2 should return 1 remaining German document")
                .hasSize(1);
    }
}
