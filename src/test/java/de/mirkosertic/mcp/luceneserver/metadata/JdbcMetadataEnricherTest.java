package de.mirkosertic.mcp.luceneserver.metadata;

import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration-style tests for {@link JdbcMetadataEnricher} using an H2 in-memory database.
 */
class JdbcMetadataEnricherTest {

    private static final String H2_URL = "jdbc:h2:mem:test_enricher;DB_CLOSE_DELAY=-1";
    private Connection h2Connection;

    @BeforeEach
    void setUp() throws Exception {
        h2Connection = DriverManager.getConnection(H2_URL, "sa", "");
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS doc_metadata ("
                    + "  file_path VARCHAR(1000) PRIMARY KEY,"
                    + "  metadata  CLOB"
                    + ")");
            stmt.execute("DELETE FROM doc_metadata");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS doc_metadata");
        }
        h2Connection.close();
    }

    private JdbcMetadataConfig buildConfig(final boolean syncEnabled) {
        return new JdbcMetadataConfig(
                true,
                H2_URL,
                "sa",
                "",
                null,
                2,
                5000,
                5000,
                "SELECT metadata FROM doc_metadata WHERE file_path = :file_path",
                List.of(new JdbcMetadataConfig.ParameterMapping("file_path", "file_path")),
                new JdbcMetadataConfig.JsonConfig("metadata"),
                new JdbcMetadataConfig.SyncConfig(syncEnabled, 5, null));
    }

    private JdbcConnectionPool buildPool(final JdbcMetadataConfig config) {
        return new JdbcConnectionPool(config);
    }

    @Test
    void testEnrichAddsDbmetaPrefix() throws Exception {
        // Insert test row
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/file.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"customer_id\", \"type\": \"keyword\", \"value\": \"C42\" }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/file.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // Field name must be "dbmeta_customer_id"
            assertThat(doc.get("dbmeta_customer_id")).isEqualTo("C42");
            // Original name must NOT appear
            assertThat(doc.getField("customer_id")).isNull();
        }
    }

    @Test
    void testFacetedFieldRegisteredInLuceneIndexService() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/a.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"department\", \"type\": \"keyword\", \"value\": \"HR\", \"faceted\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/a.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // faceted: true → registerFacetField must be called
            verify(mockIndexService).registerFacetField("dbmeta_department");
        }
    }

    @Test
    void testNonFacetedFieldNotRegistered() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/b.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"ref_no\", \"type\": \"keyword\", \"value\": \"R1\" }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/b.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // faceted: false (absent) → registerFacetField must NOT be called
            verify(mockIndexService, never()).registerFacetField(anyString());
        }
    }

    @Test
    void testNoRowInDbDoesNotFail() {
        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/does/not/exist.pdf", Field.Store.YES));

            // Must not throw
            enricher.enrich(doc, indexer);

            assertThat(doc.getFields()).hasSize(1); // only the file_path we added
        }
    }

    @Test
    void testMultiValueKeywordFacetedFieldSetsMultiValuedOnFacetsConfig() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/mv.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"tags\", \"type\": \"keyword\","
                    + "   \"values\": [\"a\",\"b\"], \"faceted\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/mv.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // FacetsConfig must be told this field is multi-valued
            assertThat(indexer.getFacetsConfig().getDimConfig("dbmeta_tags").multiValued).isTrue();
        }
    }

    @Test
    void testFieldPrefixConstant() {
        assertThat(JdbcMetadataEnricher.FIELD_PREFIX).isEqualTo("dbmeta_");
    }

    @Test
    void testLongFieldRegistersLongPointField() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/long-reg.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"amount\", \"type\": \"long\", \"value\": 42, \"searchable\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/long-reg.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            verify(mockIndexService).registerLongPointField("dbmeta_amount");
        }
    }

    @Test
    void testDateFieldRegistersDateField() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/date-reg.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"created_at\", \"type\": \"date\", \"value\": \"2023-11-14T22:13:20Z\", \"searchable\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/date-reg.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            verify(mockIndexService).registerDateField("dbmeta_created_at");
        }
    }

    @Test
    void testNonSearchableLongFieldDoesNotRegister() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/long-nosearch.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"hidden\", \"type\": \"long\", \"value\": 7, \"searchable\": false, \"stored\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/long-nosearch.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            verify(mockIndexService, never()).registerLongPointField(anyString());
        }
    }

    @Test
    void testIntFieldAdded() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/int.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"count\", \"type\": \"int\", \"value\": 42 }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/int.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // IntPoint field must exist (it has no stored value via getField for binary fields)
            assertThat(doc.getFields("dbmeta_count")).isNotEmpty();
            // StoredField for the int value must exist with name "dbmeta_count"
            assertThat(doc.getField("dbmeta_count")).isNotNull();
            assertThat(doc.getField("dbmeta_count").numericValue().intValue()).isEqualTo(42);
        }
    }

    @Test
    void testIntFieldRegistersIntPointField() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/int-reg.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"count\", \"type\": \"int\", \"value\": 7, \"searchable\": true }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/int-reg.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            verify(mockIndexService).registerIntPointField("dbmeta_count");
        }
    }

    @Test
    void testLongFieldAdded() throws Exception {
        try (final Statement stmt = h2Connection.createStatement()) {
            stmt.execute(
                    "INSERT INTO doc_metadata VALUES("
                    + "'/test/long.pdf',"
                    + "'{ \"fields\": [{ \"name\": \"amount\", \"type\": \"long\", \"value\": 9999 }] }'"
                    + ")");
        }

        final JdbcMetadataConfig config = buildConfig(false);
        final LuceneIndexService mockIndexService = Mockito.mock(LuceneIndexService.class);

        try (final JdbcConnectionPool pool = buildPool(config)) {
            final JdbcMetadataEnricher enricher = new JdbcMetadataEnricher(
                    config, pool, new SqlTemplateParser(), new JsonMetadataParser(), mockIndexService);

            final DocumentIndexer indexer = new DocumentIndexer();
            final Document doc = new Document();
            doc.add(new StringField("file_path", "/test/long.pdf", Field.Store.YES));

            enricher.enrich(doc, indexer);

            // StoredField for the long value must exist with name "dbmeta_amount"
            assertThat(doc.getField("dbmeta_amount")).isNotNull();
            assertThat(doc.getField("dbmeta_amount").numericValue().longValue()).isEqualTo(9999L);
        }
    }
}
