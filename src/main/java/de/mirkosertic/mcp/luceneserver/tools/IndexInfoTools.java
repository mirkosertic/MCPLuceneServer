package de.mirkosertic.mcp.luceneserver.tools;

import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.index.analysis.LemmatizerCacheStats;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetDocumentDetailsRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetDocumentDetailsResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.IndexStatsResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.IndexedFieldsResponse;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tools for index information: getIndexStats, listIndexedFields, getDocumentDetails.
 */
public class IndexInfoTools implements McpToolProvider {

    private static final Logger logger = LoggerFactory.getLogger(IndexInfoTools.class);

    private final LuceneIndexService indexService;
    private final QueryRuntimeStats queryRuntimeStats;

    public IndexInfoTools(final LuceneIndexService indexService, final QueryRuntimeStats queryRuntimeStats) {
        this.indexService = indexService;
        this.queryRuntimeStats = queryRuntimeStats;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Get index stats tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexStats")
                        .description("Get index statistics: document count, schema version, date field ranges, " +
                                "lemmatizer cache performance (hit rate, size, evictions per language), " +
                                "and query runtime metrics (avg/min/max duration, p50-p99 percentiles, " +
                                "per-field facet computation timing). " +
                                "Note: Query metrics are available after the first search; stats update on next search.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexStats())
                .build());

        // List indexed fields tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("listIndexedFields")
                        .description("List all field names present in the Lucene index. Useful for understanding the index schema and building queries.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> listIndexedFields())
                .build());

        // Get document details tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getDocumentDetails")
                        .description("Retrieve all stored fields and content of a document from the Lucene index by its file path. " +
                                "This tool retrieves document details and extracted content directly from the index without requiring filesystem access. " +
                                "Returns all metadata fields (file_path, file_name, file_extension, file_type, file_size, title, author, creator, subject, keywords, language, " +
                                "created_date, modified_date, indexed_date, content_hash) and the full document content. " +
                                "Content is limited to 500,000 characters (500KB) to keep response size safe - use the contentTruncated field to check if content was truncated.")
                        .inputSchema(SchemaGenerator.generateSchema(GetDocumentDetailsRequest.class))
                        .build())
                .callHandler((exchange, request) -> getDocumentDetails(request.arguments()))
                .build());

        return tools;
    }

    private McpSchema.CallToolResult getIndexStats() {
        logger.info("Index stats request");

        try {
            final long documentCount = indexService.getDocumentCount();
            final String indexPath = indexService.getIndexPath();
            final int schemaVersion = indexService.getIndexSchemaVersion();
            final String softwareVersion = BuildInfo.getVersion();
            final String buildTimestamp = BuildInfo.getBuildTimestamp();

            // Get date field ranges and convert to ISO-8601 strings
            final Map<String, long[]> ranges = indexService.getDateFieldRanges();
            final Map<String, IndexStatsResponse.DateFieldHint> dateFieldHints = new HashMap<>();
            for (final var entry : ranges.entrySet()) {
                final String minDate = java.time.Instant.ofEpochMilli(entry.getValue()[0]).toString();
                final String maxDate = java.time.Instant.ofEpochMilli(entry.getValue()[1]).toString();
                dateFieldHints.put(entry.getKey(), new IndexStatsResponse.DateFieldHint(minDate, maxDate));
            }

            // Get lemmatizer cache statistics
            final Map<String, LemmatizerCacheStats> cacheStatsMap = indexService.getLemmatizerCacheStats();
            final Map<String, IndexStatsResponse.LemmatizerCacheMetrics> lemmatizerMetrics = new HashMap<>();
            for (final var entry : cacheStatsMap.entrySet()) {
                final String language = entry.getKey();
                final LemmatizerCacheStats stats = entry.getValue();
                lemmatizerMetrics.put(language, new IndexStatsResponse.LemmatizerCacheMetrics(
                        language,
                        String.format("%.1f%%", stats.getHitRate()),
                        stats.getCacheHits(),
                        stats.getCacheMisses(),
                        stats.getCurrentSize(),
                        stats.getEvictions()
                ));
            }

            // Build query runtime metrics
            final IndexStatsResponse.QueryRuntimeMetrics queryRuntimeMetrics;
            if (queryRuntimeStats.getTotalQueries() > 0) {
                final QueryRuntimeStats.Percentiles percentiles = queryRuntimeStats.getPercentiles();
                final String averageFacetDurationMs = String.format("%.3f",
                        queryRuntimeStats.getAverageFacetDurationMicros() / 1000.0);
                final Map<String, String> perFieldAvgFacetMs = new HashMap<>();
                final Map<String, Long> perFieldCumulative = queryRuntimeStats.getPerFieldFacetDurationMicros();
                final long totalQueriesForFacets = queryRuntimeStats.getTotalQueries();
                for (final var entry : perFieldCumulative.entrySet()) {
                    perFieldAvgFacetMs.put(entry.getKey(),
                            String.format("%.3f", (double) entry.getValue() / totalQueriesForFacets / 1000.0));
                }
                queryRuntimeMetrics = new IndexStatsResponse.QueryRuntimeMetrics(
                        queryRuntimeStats.getTotalQueries(),
                        String.format("%.1f", queryRuntimeStats.getAverageDurationMs()),
                        queryRuntimeStats.getMinDurationMs(),
                        queryRuntimeStats.getMaxDurationMs(),
                        String.format("%.1f", queryRuntimeStats.getAverageHitCount()),
                        percentiles != null ? percentiles.p50() : null,
                        percentiles != null ? percentiles.p75() : null,
                        percentiles != null ? percentiles.p90() : null,
                        percentiles != null ? percentiles.p95() : null,
                        percentiles != null ? percentiles.p99() : null,
                        averageFacetDurationMs,
                        perFieldAvgFacetMs
                );
            } else {
                queryRuntimeMetrics = null;
            }

            logger.info("Index stats: {} documents, schema v{}", documentCount, schemaVersion);

            return ToolResultHelper.createResult(IndexStatsResponse.success(
                    documentCount, indexPath, schemaVersion, softwareVersion, buildTimestamp, dateFieldHints, lemmatizerMetrics, queryRuntimeMetrics));

        } catch (final IOException e) {
            logger.error("Error getting index stats", e);
            return ToolResultHelper.createResult(IndexStatsResponse.error("Error getting index stats: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult listIndexedFields() {
        logger.info("List indexed fields request");

        try {
            final Set<String> fields = indexService.getIndexedFields();

            logger.info("Listed {} indexed fields", fields.size());

            return ToolResultHelper.createResult(IndexedFieldsResponse.success(fields));

        } catch (final IOException e) {
            logger.error("Error listing indexed fields", e);
            return ToolResultHelper.createResult(IndexedFieldsResponse.error("Error listing indexed fields: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getDocumentDetails(final Map<String, Object> args) {
        final GetDocumentDetailsRequest request = GetDocumentDetailsRequest.fromMap(args);

        logger.info("Get document details request: filePath='{}'", request.filePath());

        try {
            final Map<String, Object> document = indexService.getDocumentByFilePath(request.filePath());

            if (document == null) {
                logger.warn("Document not found in index: {}", request.filePath());
                return ToolResultHelper.createResult(GetDocumentDetailsResponse.error(
                        "Document not found in index: " + request.filePath()));
            }

            logger.info("Retrieved document details for: {} (content length: {}, truncated: {})",
                    request.filePath(),
                    document.containsKey("content") ? ((String) document.get("content")).length() : 0,
                    document.getOrDefault("contentTruncated", false));

            return ToolResultHelper.createResult(GetDocumentDetailsResponse.success(document));

        } catch (final IOException e) {
            logger.error("Error retrieving document details", e);
            return ToolResultHelper.createResult(GetDocumentDetailsResponse.error("Error retrieving document: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error retrieving document details", e);
            return ToolResultHelper.createResult(GetDocumentDetailsResponse.error("Unexpected error: " + e.getMessage()));
        }
    }
}
