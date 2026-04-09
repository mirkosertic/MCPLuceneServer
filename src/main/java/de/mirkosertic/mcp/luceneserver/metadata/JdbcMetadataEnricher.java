package de.mirkosertic.mcp.luceneserver.metadata;

import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches Lucene documents with metadata loaded from a JDBC data source.
 * <p>
 * Field names are prefixed with {@link #FIELD_PREFIX} to avoid collisions with the
 * base document schema.  For a metadata field named {@code customer_id}, the Lucene
 * field will be {@code dbmeta_customer_id}.
 * <p>
 * When a field is declared {@code faceted: true} in the JSON payload, the field is
 * registered with {@link LuceneIndexService#registerFacetField(String)} so that the
 * search layer includes it in facet computations.
 */
public class JdbcMetadataEnricher {

    private static final Logger logger = LoggerFactory.getLogger(JdbcMetadataEnricher.class);

    /** Prefix applied to every metadata field name to avoid schema collisions. */
    static final String FIELD_PREFIX = "dbmeta_";

    private final JdbcMetadataConfig config;
    private final JdbcConnectionPool connectionPool;
    private final SqlTemplateParser templateParser;
    private final JsonMetadataParser jsonParser;
    private final LuceneIndexService luceneIndexService;

    public JdbcMetadataEnricher(
            final JdbcMetadataConfig config,
            final JdbcConnectionPool connectionPool,
            final SqlTemplateParser templateParser,
            final JsonMetadataParser jsonParser,
            final LuceneIndexService luceneIndexService) {
        this.config = config;
        this.connectionPool = connectionPool;
        this.templateParser = templateParser;
        this.jsonParser = jsonParser;
        this.luceneIndexService = luceneIndexService;
    }

    /**
     * Enrich the given Lucene document with metadata from the database.
     * Errors are logged and the document is indexed without enrichment (skip + warn).
     *
     * @param document       the Lucene document being indexed (will be mutated in place)
     * @param documentIndexer provides the shared FacetsConfig
     */
    public void enrich(final Document document, final DocumentIndexer documentIndexer) {
        if (!config.enabled()) {
            return;
        }

        try {
            final SqlTemplateParser.ParsedTemplate template = templateParser.parse(config.query());
            final Map<String, String> params = extractParameters(document);

            for (final String paramName : template.parameterNames()) {
                if (!params.containsKey(paramName) || params.get(paramName) == null) {
                    logger.error(
                            "Missing required parameter '{}' for metadata query — skipping enrichment",
                            paramName);
                    return;
                }
            }

            try (final Connection conn = connectionPool.getConnection();
                 final PreparedStatement stmt = conn.prepareStatement(template.sql())) {

                bindParameters(stmt, template.parameterNames(), params);
                if (config.queryTimeout() > 0) {
                    stmt.setQueryTimeout(config.queryTimeout() / 1000);
                }

                try (final ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        logger.debug("No metadata found for parameters: {}", params);
                        return;
                    }

                    final String jsonStr = rs.getString(config.json().columnName());
                    if (jsonStr == null || jsonStr.isBlank()) {
                        logger.debug("Metadata column is empty for parameters: {}", params);
                        return;
                    }

                    final List<JsonMetadataParser.MetadataField> fields = jsonParser.parse(jsonStr);
                    addFieldsToDocument(document, fields, documentIndexer);

                    if (rs.next()) {
                        logger.warn(
                                "Metadata query returned multiple rows for parameters: {} — using first row only",
                                params);
                    }
                }

            } catch (final SQLException e) {
                logger.warn(
                        "Failed to load JDBC metadata (DB error), indexing without enrichment: {}",
                        e.getMessage());
            }

        } catch (final Exception e) {
            logger.error("Metadata enrichment error: {}", e.getMessage(), e);
        }
    }

    private Map<String, String> extractParameters(final Document doc) {
        final Map<String, String> params = new HashMap<>();
        for (final JdbcMetadataConfig.ParameterMapping mapping : config.parameters()) {
            final String value = doc.get(mapping.sourceField());
            if (value != null) {
                params.put(mapping.name(), value);
            }
        }
        return params;
    }

    private void bindParameters(
            final PreparedStatement stmt,
            final List<String> names,
            final Map<String, String> values) throws SQLException {
        for (int i = 0; i < names.size(); i++) {
            stmt.setString(i + 1, values.get(names.get(i)));
        }
    }

    private void addFieldsToDocument(
            final Document doc,
            final List<JsonMetadataParser.MetadataField> fields,
            final DocumentIndexer documentIndexer) {

        for (final JsonMetadataParser.MetadataField field : fields) {
            final String fieldName = FIELD_PREFIX + field.name();

            if (doc.getField(fieldName) != null) {
                logger.error(
                        "Field collision: '{}' already exists in the base schema — skipping metadata field",
                        fieldName);
                continue;
            }

            if (field.values().isEmpty()) {
                logger.debug("Skipping empty metadata field: {}", fieldName);
                continue;
            }

            // Configure multi-valued facet before adding fields so FacetsConfig.build() works.
            if (field.faceted() && field.values().size() > 1) {
                documentIndexer.getFacetsConfig().setMultiValued(fieldName, true);
                // Also inform the search-side FacetsConfig instance.
                luceneIndexService.registerFacetField(fieldName);
            }

            for (final Object value : field.values()) {
                addLuceneField(doc, fieldName, value, field);
            }

            // Register as dynamic facet field so search facets include it.
            if (field.faceted()) {
                luceneIndexService.registerFacetField(fieldName);
            }
        }
    }

    private void addLuceneField(
            final Document doc,
            final String name,
            final Object value,
            final JsonMetadataParser.MetadataField field) {

        switch (field.type()) {
            case INT -> {
                final int intValue = (Integer) value;
                if (field.searchable()) {
                    doc.add(new IntPoint(name, intValue));
                    luceneIndexService.registerIntPointField(name);
                }
                if (field.stored()) {
                    doc.add(new StoredField(name, intValue));
                }
                if (field.faceted()) {
                    doc.add(new SortedSetDocValuesFacetField(name, String.valueOf(intValue)));
                }
            }
            case KEYWORD -> {
                if (field.searchable()) {
                    doc.add(new StringField(name, value.toString(),
                            field.stored() ? Field.Store.YES : Field.Store.NO));
                }
                if (field.faceted()) {
                    doc.add(new SortedSetDocValuesFacetField(name, value.toString()));
                }
            }
            case TEXT -> {
                if (field.searchable()) {
                    doc.add(new TextField(name, value.toString(),
                            field.stored() ? Field.Store.YES : Field.Store.NO));
                }
                // TEXT fields are intentionally not faceted (cardinality too high)
            }
            case LONG -> {
                final long longValue = (Long) value;
                if (field.searchable()) {
                    doc.add(new LongPoint(name, longValue));
                    luceneIndexService.registerLongPointField(name);
                }
                if (field.stored()) {
                    doc.add(new StoredField(name, longValue));
                }
                if (field.faceted()) {
                    // Use string representation for SortedSet-based faceting
                    doc.add(new SortedSetDocValuesFacetField(name, String.valueOf(longValue)));
                }
            }
            case DATE -> {
                final long epochMillis = (Long) value;
                if (field.searchable()) {
                    doc.add(new LongPoint(name, epochMillis));
                    luceneIndexService.registerDateField(name);
                }
                if (field.stored()) {
                    doc.add(new StoredField(name, epochMillis));
                }
                // DATE fields use range queries; faceting is not typically useful
            }
            default -> logger.warn("Unknown field type {} for field '{}' — skipping", field.type(), name);
        }
    }
}
