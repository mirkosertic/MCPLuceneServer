package de.mirkosertic.mcp.luceneserver.crawler;

import org.apache.lucene.document.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DocumentIndexer, focusing on metadata extraction with fallback support.
 */
@DisplayName("DocumentIndexer Tests")
class DocumentIndexerTest {

    private DocumentIndexer documentIndexer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        documentIndexer = new DocumentIndexer();
    }

    @Nested
    @DisplayName("Metadata Extraction with Fallbacks")
    class MetadataFallbackTests {

        private Path testFile;

        @BeforeEach
        void createTestFile() throws IOException {
            testFile = tempDir.resolve("test-document.txt");
            Files.writeString(testFile, "Test content");
        }

        @Test
        @DisplayName("Should extract title from dc:title (Tika standard)")
        void shouldExtractTitleFromDcTitle() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:title", "Tika Standard Title");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isEqualTo("Tika Standard Title");
        }

        @Test
        @DisplayName("Should fall back to 'title' key when dc:title is missing")
        void shouldFallBackToTitleKey() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("title", "Legacy Title");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isEqualTo("Legacy Title");
        }

        @Test
        @DisplayName("Should fall back to 'Title' key when dc:title and title are missing")
        void shouldFallBackToCapitalizedTitleKey() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("Title", "Capitalized Title");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isEqualTo("Capitalized Title");
        }

        @Test
        @DisplayName("Should prefer dc:title over legacy title keys")
        void shouldPreferDcTitleOverLegacy() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:title", "Preferred Title");
            metadata.put("title", "Legacy Title");
            metadata.put("Title", "Capitalized Title");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isEqualTo("Preferred Title");
        }

        @Test
        @DisplayName("Should extract author from dc:creator (Tika standard)")
        void shouldExtractAuthorFromDcCreator() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:creator", "Tika Author");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("author")).isEqualTo("Tika Author");
        }

        @Test
        @DisplayName("Should fall back to meta:author when dc:creator is missing")
        void shouldFallBackToMetaAuthor() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("meta:author", "Meta Author");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("author")).isEqualTo("Meta Author");
        }

        @Test
        @DisplayName("Should fall back to 'Author' key when dc:creator and meta:author are missing")
        void shouldFallBackToCapitalizedAuthor() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("Author", "Capitalized Author");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("author")).isEqualTo("Capitalized Author");
        }

        @Test
        @DisplayName("Should fall back to 'author' key as last resort")
        void shouldFallBackToLowercaseAuthor() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("author", "Lowercase Author");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("author")).isEqualTo("Lowercase Author");
        }

        @Test
        @DisplayName("Should prefer dc:creator over legacy author keys")
        void shouldPreferDcCreatorOverLegacy() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:creator", "Preferred Author");
            metadata.put("meta:author", "Meta Author");
            metadata.put("Author", "Capitalized Author");
            metadata.put("author", "Lowercase Author");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("author")).isEqualTo("Preferred Author");
        }

        @Test
        @DisplayName("Should extract creator from xmp:CreatorTool (Tika standard)")
        void shouldExtractCreatorFromXmpCreatorTool() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("xmp:CreatorTool", "Adobe Acrobat");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("creator")).isEqualTo("Adobe Acrobat");
        }

        @Test
        @DisplayName("Should fall back to 'creator' key when xmp:CreatorTool is missing")
        void shouldFallBackToCreatorKey() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("creator", "Legacy Creator");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("creator")).isEqualTo("Legacy Creator");
        }

        @Test
        @DisplayName("Should fall back to Application-Name when other creator keys missing")
        void shouldFallBackToApplicationName() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("Application-Name", "Microsoft Word");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("creator")).isEqualTo("Microsoft Word");
        }

        @Test
        @DisplayName("Should extract subject from dc:subject (Tika standard)")
        void shouldExtractSubjectFromDcSubject() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:subject", "Machine Learning");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("subject")).isEqualTo("Machine Learning");
        }

        @Test
        @DisplayName("Should fall back to 'subject' key when dc:subject is missing")
        void shouldFallBackToSubjectKey() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("subject", "Legacy Subject");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("subject")).isEqualTo("Legacy Subject");
        }

        @Test
        @DisplayName("Should extract keywords from meta:keyword (Tika standard)")
        void shouldExtractKeywordsFromMetaKeyword() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("meta:keyword", "java, lucene, search");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("keywords")).isEqualTo("java, lucene, search");
        }

        @Test
        @DisplayName("Should fall back to 'Keywords' key when meta:keyword is missing")
        void shouldFallBackToKeywordsKey() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("Keywords", "Legacy Keywords");
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("keywords")).isEqualTo("Legacy Keywords");
        }

        @Test
        @DisplayName("Should skip empty metadata values")
        void shouldSkipEmptyMetadataValues() {
            // Given
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("dc:title", "");  // Empty should be skipped
            metadata.put("title", "Fallback Title");  // This should be used
            final ExtractedDocument extracted = createExtractedDocument(metadata);

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isEqualTo("Fallback Title");
        }

        @Test
        @DisplayName("Should handle null metadata map")
        void shouldHandleNullMetadata() {
            // Given - ExtractedDocument(content, metadata, detectedLanguage, fileType, fileSize)
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "text/plain", 100L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isNull();
            assertThat(doc.get("author")).isNull();
            assertThat(doc.get("creator")).isNull();
            assertThat(doc.get("subject")).isNull();
            assertThat(doc.get("keywords")).isNull();
        }

        @Test
        @DisplayName("Should handle empty metadata map")
        void shouldHandleEmptyMetadata() {
            // Given
            final ExtractedDocument extracted = createExtractedDocument(new HashMap<>());

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("title")).isNull();
            assertThat(doc.get("author")).isNull();
        }

        // ExtractedDocument(content, metadata, detectedLanguage, fileType, fileSize)
        private ExtractedDocument createExtractedDocument(final Map<String, String> metadata) {
            return new ExtractedDocument("Test content", metadata, "en", "text/plain", 100L);
        }
    }

    @Nested
    @DisplayName("Core Document Fields")
    class CoreDocumentFieldTests {

        // ExtractedDocument(content, metadata, detectedLanguage, fileType, fileSize)

        @Test
        @DisplayName("Should set file_path field")
        void shouldSetFilePathField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "text/plain", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("file_path")).isEqualTo(testFile.toString());
        }

        @Test
        @DisplayName("Should set file_name field")
        void shouldSetFileNameField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("my-document.txt");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "text/plain", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("file_name")).isEqualTo("my-document.txt");
        }

        @Test
        @DisplayName("Should set file_extension field")
        void shouldSetFileExtensionField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("document.PDF");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "application/pdf", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("file_extension")).isEqualTo("pdf");  // Should be lowercase
        }

        @Test
        @DisplayName("Should set file_type field from extracted document")
        void shouldSetFileTypeField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("test.pdf");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "application/pdf", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("file_type")).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("Should set content field")
        void shouldSetContentField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "Test file content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "Extracted text content", null, null, "text/plain", 17L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("content")).isEqualTo("Extracted text content");
        }

        @Test
        @DisplayName("Should set language field when detected")
        void shouldSetLanguageField() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, "de", "text/plain", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("language")).isEqualTo("de");
        }

        @Test
        @DisplayName("Should calculate content hash")
        void shouldCalculateContentHash() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "Test content for hashing", null, null, "text/plain", 24L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("content_hash"))
                    .isNotNull()
                    .hasSize(64);  // SHA-256 produces 64 hex characters
        }

        @Test
        @DisplayName("Should not set file_extension for files without extension")
        void shouldNotSetFileExtensionForFilesWithoutExtension() throws IOException {
            // Given
            final Path testFile = tempDir.resolve("README");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "text/plain", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            assertThat(doc.get("file_extension")).isNull();
        }

        @Test
        @DisplayName("Should not set file_extension for dotfiles")
        void shouldNotSetFileExtensionForDotfiles() throws IOException {
            // Given
            final Path testFile = tempDir.resolve(".gitignore");
            Files.writeString(testFile, "content");
            final ExtractedDocument extracted = new ExtractedDocument(
                    "content", null, null, "text/plain", 7L
            );

            // When
            final Document doc = documentIndexer.createDocument(testFile, extracted);

            // Then
            // .gitignore has dot at position 0, so lastDot (0) is not > 0
            assertThat(doc.get("file_extension")).isNull();
        }
    }

    @Nested
    @DisplayName("FacetsConfig")
    class FacetsConfigTests {

        @Test
        @DisplayName("Should have FacetsConfig available")
        void shouldHaveFacetsConfigAvailable() {
            // FacetsConfig is used internally by DocumentIndexer for faceting
            // We verify it's properly initialized
            assertThat(documentIndexer.getFacetsConfig()).isNotNull();
        }
    }
}
