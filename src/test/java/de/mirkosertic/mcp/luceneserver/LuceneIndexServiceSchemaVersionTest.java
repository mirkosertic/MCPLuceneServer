package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for schema version management in LuceneIndexService.
 */
@DisplayName("Schema Version Management Tests")
class LuceneIndexServiceSchemaVersionTest {

    @TempDir
    Path tempDir;

    private Path indexDir;
    private ApplicationConfig config;
    private DocumentIndexer documentIndexer;
    private LuceneIndexService indexService;

    @BeforeEach
    void setUp() {
        indexDir = tempDir.resolve("index");

        // Create mock config
        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);

        documentIndexer = new DocumentIndexer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (indexService != null) {
            indexService.close();
        }
    }

    @Test
    @DisplayName("New empty index should not require schema upgrade")
    void newEmptyIndexShouldNotRequireUpgrade() throws IOException {
        // When: Initialize a new index
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Then: No upgrade should be required
        assertThat(indexService.isSchemaUpgradeRequired())
                .as("New index should not require upgrade")
                .isFalse();

        // And: Schema version should be current
        assertThat(indexService.getIndexSchemaVersion())
                .as("Schema version should be current")
                .isEqualTo(DocumentIndexer.SCHEMA_VERSION);

        // And: Software version should be set
        assertThat(indexService.getIndexSoftwareVersion())
                .as("Software version should be set")
                .isEqualTo(BuildInfo.getVersion());
    }

    @Test
    @DisplayName("Index with matching schema version should not require upgrade")
    void indexWithMatchingVersionShouldNotRequireUpgrade() throws IOException {
        // Given: Create an index with current schema version
        createIndexWithSchemaVersion(DocumentIndexer.SCHEMA_VERSION);

        // When: Initialize service
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Then: No upgrade should be required
        assertThat(indexService.isSchemaUpgradeRequired())
                .as("Index with matching schema version should not require upgrade")
                .isFalse();
    }

    @Test
    @DisplayName("Index with older schema version should require upgrade")
    void indexWithOlderVersionShouldRequireUpgrade() throws IOException {
        // Given: Create an index with an older schema version
        final int olderVersion = DocumentIndexer.SCHEMA_VERSION - 1;
        createIndexWithSchemaVersion(olderVersion);

        // When: Initialize service
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Then: Upgrade should be required
        assertThat(indexService.isSchemaUpgradeRequired())
                .as("Index with older schema version should require upgrade")
                .isTrue();

        // And: After init, the stored version should be updated to current
        assertThat(indexService.getIndexSchemaVersion())
                .as("Schema version should be updated to current after init")
                .isEqualTo(DocumentIndexer.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("Legacy index without schema version should require upgrade")
    void legacyIndexWithoutVersionShouldRequireUpgrade() throws IOException {
        // Given: Create a legacy index without schema version metadata
        createLegacyIndexWithoutVersion();

        // When: Initialize service
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Then: Upgrade should be required
        assertThat(indexService.isSchemaUpgradeRequired())
                .as("Legacy index without schema version should require upgrade")
                .isTrue();

        // And: After init, the schema version should be set
        assertThat(indexService.getIndexSchemaVersion())
                .as("Schema version should be set after init")
                .isEqualTo(DocumentIndexer.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("Commit user data should contain correct schema and software version after init")
    void commitUserDataShouldBeCorrectAfterInit() throws IOException {
        // When: Initialize a new index
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Then: Schema version should be correct
        assertThat(indexService.getIndexSchemaVersion())
                .as("Schema version should be current")
                .isEqualTo(DocumentIndexer.SCHEMA_VERSION);

        // And: Software version should be correct
        final String softwareVersion = indexService.getIndexSoftwareVersion();
        assertThat(softwareVersion)
                .as("Software version should not be empty")
                .isNotEmpty();

        assertThat(softwareVersion)
                .as("Software version should match BuildInfo")
                .isEqualTo(BuildInfo.getVersion());
    }

    /**
     * Helper method to create an index with a specific schema version.
     */
    private void createIndexWithSchemaVersion(final int schemaVersion) throws IOException {
        try (final FSDirectory directory = FSDirectory.open(indexDir)) {
            final IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

            try (final IndexWriter writer = new IndexWriter(directory, config)) {
                // Add a dummy document so the index is not empty
                final Document doc = new Document();
                doc.add(new StringField("file_path", "/test/document.txt", Field.Store.YES));
                writer.addDocument(doc);

                // Set commit user data with the specified schema version
                writer.setLiveCommitData(Map.of(
                        "schema_version", String.valueOf(schemaVersion),
                        "software_version", "test-version"
                ).entrySet());

                writer.commit();
            }
        }
    }

    /**
     * Helper method to create a legacy index without schema version metadata.
     */
    private void createLegacyIndexWithoutVersion() throws IOException {
        try (final FSDirectory directory = FSDirectory.open(indexDir)) {
            final IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

            try (final IndexWriter writer = new IndexWriter(directory, config)) {
                // Add a dummy document so the index is not empty
                final Document doc = new Document();
                doc.add(new StringField("file_path", "/test/document.txt", Field.Store.YES));
                writer.addDocument(doc);

                // Commit without setting any user data (simulates legacy index)
                writer.commit();
            }
        }
    }
}
