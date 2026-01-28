package de.mirkosertic.mcp.luceneserver.crawler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for FileContentExtractor covering all supported file formats.
 * Each test verifies that content can be extracted from a specific format.
 */
@DisplayName("FileContentExtractor Tests")
class FileContentExtractorTest {

    @TempDir
    static Path tempDir;

    static FileContentExtractor extractor;

    @BeforeAll
    static void setUp() {
        // Create a mock CrawlerProperties with default settings
        final CrawlerConfigurationManager configManager = mock(CrawlerConfigurationManager.class);
        when(configManager.loadDirectories()).thenReturn(java.util.List.of());
        when(configManager.getConfigPath()).thenReturn(Path.of("/mock/config.yaml"));

        final CrawlerProperties properties = new CrawlerProperties(configManager);
        properties.setExtractMetadata(true);
        properties.setDetectLanguage(true);
        properties.setMaxContentLength(-1); // Unlimited

        extractor = new FileContentExtractor(properties);
    }

    /**
     * Provides test arguments for each supported file format.
     * Each argument contains: extension, file generator, expected MIME type pattern.
     */
    static Stream<Arguments> fileFormatProvider() {
        return Stream.of(
                Arguments.of("txt", (FileGenerator) TestDocumentGenerator::createTxtFile, "text/plain"),
                Arguments.of("pdf", (FileGenerator) TestDocumentGenerator::createPdfFile, "application/pdf"),
                Arguments.of("docx", (FileGenerator) TestDocumentGenerator::createDocxFile, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of("doc", (FileGenerator) TestDocumentGenerator::createDocFile, "application/rtf"), // RTF is detected for our test file
                Arguments.of("xlsx", (FileGenerator) TestDocumentGenerator::createXlsxFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of("xls", (FileGenerator) TestDocumentGenerator::createXlsFile, "application/vnd.ms-excel"),
                Arguments.of("pptx", (FileGenerator) TestDocumentGenerator::createPptxFile, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                Arguments.of("ppt", (FileGenerator) TestDocumentGenerator::createPptFile, "application/vnd.ms-powerpoint"),
                Arguments.of("odt", (FileGenerator) TestDocumentGenerator::createOdtFile, "application/vnd.oasis.opendocument.text"),
                Arguments.of("ods", (FileGenerator) TestDocumentGenerator::createOdsFile, "application/vnd.oasis.opendocument.spreadsheet")
        );
    }

    @ParameterizedTest(name = "{0} file extraction")
    @MethodSource("fileFormatProvider")
    @DisplayName("Should extract content from")
    void shouldExtractContentFromFile(final String extension, final FileGenerator generator, final String expectedMimeType) throws Exception {
        // Given: Create a test file with known content
        final Path testFile = tempDir.resolve("test." + extension);
        generator.generate(testFile);

        // When: Extract content from the file
        final ExtractedDocument result = extractor.extract(testFile);

        // Then: Verify content was extracted
        assertThat(result).isNotNull();
        assertThat(result.content())
                .as("Content should contain test text for %s file", extension)
                .containsIgnoringCase(TestDocumentGenerator.TEST_CONTENT);

        // Verify file size is positive
        assertThat(result.fileSize())
                .as("File size should be positive for %s file", extension)
                .isGreaterThan(0);

        // Verify MIME type detection
        assertThat(result.fileType())
                .as("MIME type for %s file", extension)
                .isEqualTo(expectedMimeType);
    }

    @ParameterizedTest(name = "{0} file metadata extraction")
    @MethodSource("metadataFileFormatProvider")
    @DisplayName("Should extract metadata from")
    void shouldExtractMetadataFromFile(final String extension, final FileGenerator generator, final String titleKey, final String authorKey) throws Exception {
        // Given: Create a test file with known metadata
        final Path testFile = tempDir.resolve("test-metadata." + extension);
        generator.generate(testFile);

        // When: Extract content from the file
        final ExtractedDocument result = extractor.extract(testFile);

        // Then: Verify metadata was extracted
        assertThat(result.metadata())
                .as("Metadata should not be empty for %s file", extension)
                .isNotEmpty();

        // Check for title (if the format supports it)
        if (titleKey != null) {
            assertThat(result.metadata())
                    .as("Should contain title metadata for %s file", extension)
                    .containsKey(titleKey);
        }

        // Check for author (if the format supports it)
        if (authorKey != null) {
            assertThat(result.metadata())
                    .as("Should contain title metadata for %s file", extension)
                    .containsKey(authorKey);
        }
    }

    /**
     * Provides test arguments for metadata extraction tests.
     * Only includes formats that reliably support metadata extraction.
     */
    static Stream<Arguments> metadataFileFormatProvider() {
        return Stream.of(
                Arguments.of("pdf", (FileGenerator) TestDocumentGenerator::createPdfFile, "dc:title", "dc:creator"),
                Arguments.of("docx", (FileGenerator) TestDocumentGenerator::createDocxFile, "dc:title", "dc:creator"),
                Arguments.of("xlsx", (FileGenerator) TestDocumentGenerator::createXlsxFile, "dc:title", "dc:creator"),
                Arguments.of("pptx", (FileGenerator) TestDocumentGenerator::createPptxFile, "dc:title", "dc:creator"),
                Arguments.of("xls", (FileGenerator) TestDocumentGenerator::createXlsFile, "dc:title", "dc:creator"),
                Arguments.of("odt", (FileGenerator) TestDocumentGenerator::createOdtFile, "dc:title", "dc:creator"),
                Arguments.of("ods", (FileGenerator) TestDocumentGenerator::createOdsFile, "dc:title", "dc:creator")
        );
    }

    /**
     * Functional interface for file generation methods.
     */
    @FunctionalInterface
    interface FileGenerator {
        void generate(Path path) throws Exception;
    }

    @ParameterizedTest(name = "{0} content verification")
    @MethodSource("contentVerificationProvider")
    @DisplayName("Should extract exact test content from")
    void shouldExtractExactContent(final String extension, final FileGenerator generator) throws Exception {
        // Given: Create a test file
        final Path testFile = tempDir.resolve("exact-content-test." + extension);
        generator.generate(testFile);

        // When: Extract content
        final ExtractedDocument result = extractor.extract(testFile);

        // Then: Content should contain our test string
        assertThat(result.content())
                .as("Extracted content from %s should contain the test content", extension)
                .contains("test content")
                .contains("document extraction")
                .contains("verification");
    }

    static Stream<Arguments> contentVerificationProvider() {
        return Stream.of(
                Arguments.of("txt", (FileGenerator) TestDocumentGenerator::createTxtFile),
                Arguments.of("pdf", (FileGenerator) TestDocumentGenerator::createPdfFile),
                Arguments.of("docx", (FileGenerator) TestDocumentGenerator::createDocxFile),
                Arguments.of("doc", (FileGenerator) TestDocumentGenerator::createDocFile),
                Arguments.of("xlsx", (FileGenerator) TestDocumentGenerator::createXlsxFile),
                Arguments.of("xls", (FileGenerator) TestDocumentGenerator::createXlsFile),
                Arguments.of("pptx", (FileGenerator) TestDocumentGenerator::createPptxFile),
                Arguments.of("ppt", (FileGenerator) TestDocumentGenerator::createPptFile),
                Arguments.of("odt", (FileGenerator) TestDocumentGenerator::createOdtFile),
                Arguments.of("ods", (FileGenerator) TestDocumentGenerator::createOdsFile)
        );
    }
}
