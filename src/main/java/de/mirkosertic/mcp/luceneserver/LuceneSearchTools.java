package de.mirkosertic.mcp.luceneserver;

import com.google.common.io.Resources;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.index.analysis.LemmatizerCacheStats;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Lucene search operations.
 * Provides search, crawling, and index management functionality.
 */
public class LuceneSearchTools {

    private static final Logger logger = LoggerFactory.getLogger(LuceneSearchTools.class);

    private static final String SEARCH_DESCRIPTION =
            "Search documents using Lucene query syntax with Snowball stemming for German and English " +
            "(morphological variants found automatically, e.g. 'Vertrag' matches 'Verträge'/'Vertrages'). Exact matches rank highest.\n\n" +
            "SYNTAX:\n" +
            "- Terms: 'hello world' → AND (implicit); phrases: \"exact phrase\"\n" +
            "- Boolean: AND, OR, NOT, grouping with ()\n" +
            "- Wildcards: word* (suffix, BM25 if >=4 chars prefix), ? (single char), *word (leading, fast via reversed field), *word* (contains, constant score)\n" +
            "- Fuzzy: term~2; Proximity: \"phrase\"~5\n" +
            "- Multi-word phrases auto-expanded: \"Domain Design\" → exact(2x boost) OR near(slop=3)\n" +
            "- Field-specific: field:value\n\n" +
            "FILTERS (metadata, use 'filters' array):\n" +
            "- operators: eq (default), in, not, not_in, range\n" +
            "- faceted fields (DrillSideways, OR within same field): language, file_extension, file_type, author\n" +
            "- string fields (exact match): file_path, content_hash\n" +
            "- numeric/date fields (range supported): file_size, created_date, modified_date, indexed_date\n" +
            "- dates: ISO-8601 (2024-01-15 / 2024-01-15T10:30:00 / 2024-01-15T10:30:00Z)\n" +
            "- logic: different fields=AND, same faceted field=OR, not/not_in=MUST_NOT\n\n" +
            "SORT: sortBy=_score (default), modified_date, created_date, file_size; sortOrder=desc (default), asc\n\n" +
            "BEST PRACTICES:\n" +
            "- Synonyms: use OR — (contract OR agreement), not automatic\n" +
            "- German compounds: use *vertrag (finds Arbeitsvertrag, Mietvertrag, etc.)\n" +
            "- Multilingual: (contract OR Vertrag OR contrat)\n" +
            "- ICU folding active: Müller≈Muller, café≈cafe\n" +
            "- Irregular verbs: use OR — (run OR running OR ran)\n\n" +
            "NOT SUPPORTED: automatic synonyms, phonetic matching, semantic search, stemming for non-DE/EN languages";

    private static final String ADMIN_APP_RESOURCE_ID = "ui://indexadmin/index.html";

    private final LuceneIndexService indexService;
    private final DocumentCrawlerService crawlerService;
    private final CrawlerConfigurationManager configManager;
    private final QueryRuntimeStats queryRuntimeStats;

    public LuceneSearchTools(final LuceneIndexService indexService,
                             final DocumentCrawlerService crawlerService,
                             final CrawlerConfigurationManager configManager,
                             final QueryRuntimeStats queryRuntimeStats) {
        this.indexService = indexService;
        this.crawlerService = crawlerService;
        this.configManager = configManager;
        this.queryRuntimeStats = queryRuntimeStats;
    }

    public List<McpServerFeatures.SyncResourceSpecification> getResourceSpecifications() {
        final List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

        resources.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder()
                        .uri(ADMIN_APP_RESOURCE_ID)
                        .mimeType("text/html;profile=mcp-app")
                        .name("Index Administration")
                        .meta(Map.of("ui", Map.of("border", "true")))
                        .build(),
                this::indexAdminResource
        ));


        return resources;
    }

    private McpSchema.ReadResourceResult indexAdminResource(final McpSyncServerExchange exchange, final McpSchema.ReadResourceRequest request) {
        final URL url = Resources.getResource("indexadmin-app.html");
        try {
            final String htmlcpde = Resources.toString(url, StandardCharsets.UTF_8);

            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            htmlcpde)));
        } catch (final Exception e) {
            logger.error("Error loading index admin app", e);
            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            "")));
        }
    }


    /**
     * Returns all MCP tool specifications for registration with the MCP server.
     */
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Index admin MCP App (https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("indexAdmin")
                        .description("MCP App for Lucene index administrative tasks and maintenance")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .meta(Map.of("ui", Map.of("resourceUri", ADMIN_APP_RESOURCE_ID),
                                "ui/resourceUri", ADMIN_APP_RESOURCE_ID))
                        .build())
                .callHandler((exchange, request) -> indexAdmin(request.arguments()))
                .build());

        // Search tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("search")
                        .description(SEARCH_DESCRIPTION)
                        .inputSchema(SchemaGenerator.generateSchema(SearchRequest.class))
                        .build())
                .callHandler((exchange, request) -> search(request.arguments()))
                .build());

        // Profile query tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("profileQuery")
                        .description("Debug and optimize search queries. Analyzes query structure, scoring, filter impact, and performance.\n\n" +
                                "ANALYSIS LEVELS (cumulative):\n" +
                                "- Level 1 (always, ~5-10ms): query structure, rewrites (e.g. *vertrag→reversed field), term stats (df/IDF/rarity), search metrics, cost estimates per clause\n" +
                                "- Level 2 (analyzeFilterImpact=true, ~50-200ms): per-filter selectivity (low/medium/high/very high) and timing; costs N+1 queries\n" +
                                "- Level 3 (analyzeDocumentScoring=true, ~100-300ms): BM25 breakdown per top doc, term contribution%; maxDocExplanations default=5 (max 10)\n" +
                                "- Level 4 (analyzeFacetCost=true, ~20-50ms): facet computation overhead per dimension; costs 2 queries\n" +
                                "- All levels combined: ~200-500ms\n\n" +
                                "Returns structured output with actionable recommendations. " +
                                "Start with Level 1 (fast, often sufficient). Enable deeper levels only for specific debugging: Level 2 for filter tuning, Level 3 for ranking issues.\n\n" +
                                "LIMITATIONS: explains only matched documents; wildcard internals opaque; no passage-level scoring")
                        .inputSchema(SchemaGenerator.generateSchema(ProfileQueryRequest.class))
                        .build())
                .callHandler((exchange, request) -> profileQuery(request.arguments()))
                .build());

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

        // Start crawl tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("startCrawl")
                        .description("Start crawling configured directories. Indexes Office docs, PDFs, emails, markdown. " +
                                "Extracts metadata and detects language automatically. Use getCrawlerStats to monitor progress.")
                        .inputSchema(SchemaGenerator.generateSchema(StartCrawlRequest.class))
                        .build())
                .callHandler((exchange, request) -> startCrawl(request.arguments()))
                .build());

        // Get crawler stats tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getCrawlerStats")
                        .description("Get real-time statistics about the crawler progress, including files processed, throughput, and per-directory stats.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getCrawlerStats())
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

        // Suggest terms tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("suggestTerms")
                        .description("Suggest index terms matching a prefix. Useful for discovering vocabulary, " +
                                "finding German compound words (prefix on 'content'), exploring author names, " +
                                "or auto-completing field values. Returns terms sorted by document frequency. " +
                                "For analyzed fields (content, title, etc.), the prefix is automatically lowercased to match indexed tokens.")
                        .inputSchema(SchemaGenerator.generateSchema(SuggestTermsRequest.class))
                        .build())
                .callHandler((exchange, request) -> suggestTerms(request.arguments()))
                .build());

        // Get top terms tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getTopTerms")
                        .description("Get the most frequent terms in a field. Useful for understanding index vocabulary, " +
                                "discovering common values (languages, file types, authors), and identifying dominant terms in content. " +
                                "Returns terms sorted by document frequency. " +
                                "Warning: On large content fields this enumerates all terms — use suggestTerms with a prefix for targeted exploration.")
                        .inputSchema(SchemaGenerator.generateSchema(GetTopTermsRequest.class))
                        .build())
                .callHandler((exchange, request) -> getTopTerms(request.arguments()))
                .build());

        // Pause crawler tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("pauseCrawler")
                        .description("Pause an ongoing crawl operation. The crawler can be resumed later.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> pauseCrawler())
                .build());

        // Resume crawler tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("resumeCrawler")
                        .description("Resume a paused crawl operation.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> resumeCrawler())
                .build());

        // Get crawler status tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getCrawlerStatus")
                        .description("Get the current state of the crawler (IDLE, CRAWLING, PAUSED, or WATCHING).")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getCrawlerStatus())
                .build());

        // List crawlable directories tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("listCrawlableDirectories")
                        .description("List all configured crawlable directories. " +
                                "Shows directories from ~/.mcplucene/config.yaml or environment variable override.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> listCrawlableDirectories())
                .build());

        // Add crawlable directory tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("addCrawlableDirectory")
                        .description("Add a directory to the crawler configuration. " +
                                "The directory will be persisted to ~/.mcplucene/config.yaml and " +
                                "automatically crawled on next startup. If the crawler is currently " +
                                "watching directories, this directory will be added to the watch list.")
                        .inputSchema(SchemaGenerator.generateSchema(AddDirectoryRequest.class))
                        .build())
                .callHandler((exchange, request) -> addCrawlableDirectory(request.arguments()))
                .build());

        // Remove crawlable directory tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("removeCrawlableDirectory")
                        .description("Remove a directory from the crawler configuration. " +
                                "The change will be persisted to ~/.mcplucene/config.yaml. " +
                                "Note: This does not remove already indexed documents from the directory.")
                        .inputSchema(SchemaGenerator.generateSchema(RemoveDirectoryRequest.class))
                        .build())
                .callHandler((exchange, request) -> removeCrawlableDirectory(request.arguments()))
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

        // Unlock index tool (dangerous recovery operation)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("unlockIndex")
                        .description("Remove the write.lock file from the Lucene index directory. " +
                                "WARNING: This is a dangerous recovery operation. Only use if you are CERTAIN no other process is using the index. " +
                                "Unlocking an index that is actively being written to can cause data corruption. " +
                                "Requires explicit confirmation (confirm=true) to proceed.")
                        .inputSchema(SchemaGenerator.generateSchema(UnlockIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> unlockIndex(request.arguments()))
                .build());

        // Optimize index tool (long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("optimizeIndex")
                        .description("Optimize the Lucene index by merging segments. This is a long-running operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Optimization improves search performance but temporarily increases disk usage during the merge. " +
                                "Cannot run while the crawler is active.")
                        .inputSchema(SchemaGenerator.generateSchema(OptimizeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> optimizeIndex(request.arguments()))
                .build());

        // Purge index tool (destructive, long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("purgeIndex")
                        .description("Delete all documents from the Lucene index. This is a destructive operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Requires explicit confirmation (confirm=true) to proceed. " +
                                "Set fullPurge=true to also delete index files and reinitialize (reclaims disk space immediately).")
                        .inputSchema(SchemaGenerator.generateSchema(PurgeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> purgeIndex(request.arguments()))
                .build());

        // Get index admin status tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexAdminStatus")
                        .description("Get the status of long-running index administration operations (optimize, purge). " +
                                "Returns the current state (IDLE, OPTIMIZING, PURGING, COMPLETED, FAILED), progress percentage, " +
                                "progress message, elapsed time, and the result of the last completed operation.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexAdminStatus())
                .build());

        return tools;
    }

    // Tool implementation methods
    private McpSchema.CallToolResult indexAdmin(final Map<String, Object> args) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(ToolResultHelper.toJson(new HashMap<>()))))
                .build();
    }

    private McpSchema.CallToolResult search(final Map<String, Object> args) {
        final SearchRequest request = SearchRequest.fromMap(args);
        final List<SearchFilter> effectiveFilters = request.effectiveFilters();

        // Validate sort parameters
        String sortError = SearchRequest.validateSortBy(request.sortBy());
        if (sortError != null) {
            logger.warn("Invalid sortBy parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }
        sortError = SearchRequest.validateSortOrder(request.sortOrder());
        if (sortError != null) {
            logger.warn("Invalid sortOrder parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }

        logger.info("Search request: query='{}', filters={}, page={}, pageSize={}, sortBy={}, sortOrder={}",
                request.query(), effectiveFilters.size(), request.page(), request.pageSize(),
                request.effectiveSortBy(), request.effectiveSortOrder());

        try {
            final long startTime = System.nanoTime();
            final LuceneIndexService.SearchResult result = indexService.search(
                    request.effectiveQuery(),
                    effectiveFilters,
                    request.effectivePage(),
                    request.effectivePageSize(),
                    request.effectiveSortBy(),
                    request.effectiveSortOrder(),
                    request.effectiveUseVectorSearch());
            final long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            queryRuntimeStats.recordQuery(durationMs, result.totalHits(),
                    result.facetTotalDurationMicros(), result.facetFieldDurationMicros());

            // Convert facets to DTO format
            final Map<String, List<SearchResponse.FacetValue>> facets = result.facets().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream()
                                    .map(fv -> new SearchResponse.FacetValue(fv.value(), fv.count()))
                                    .collect(Collectors.toList())
                    ));

            final SearchResponse response = SearchResponse.success(
                    result.documents(),
                    result.totalHits(),
                    result.page(),
                    result.pageSize(),
                    result.totalPages(),
                    result.hasNextPage(),
                    result.hasPreviousPage(),
                    facets,
                    result.activeFilters(),
                    durationMs
            );

            logger.info("Search completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions, {} active filters",
                    durationMs, result.totalHits(), result.page(), result.totalPages(),
                    result.facets().size(), result.activeFilters().size());

            return ToolResultHelper.createResult(response);

        } catch (final IllegalArgumentException e) {
            logger.warn("Invalid filter: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error("Invalid filter: " + e.getMessage()));
        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error("Invalid query syntax: " + e.getMessage()));
        } catch (final IOException e) {
            logger.error("Search error", e);
            return ToolResultHelper.createResult(SearchResponse.error("Search error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult profileQuery(final Map<String, Object> args) {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(args);

        logger.info("Profile query request: query='{}', analyzeFilterImpact={}, analyzeDocScoring={}, analyzeFacetCost={}",
                request.query(), request.effectiveAnalyzeFilterImpact(),
                request.effectiveAnalyzeDocumentScoring(), request.effectiveAnalyzeFacetCost());

        try {
            final ProfileQueryResponse response = indexService.profileQuery(request);
            return ToolResultHelper.createResult(response);
        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Invalid query syntax: " + e.getMessage()));
        } catch (final IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Invalid request: " + e.getMessage()));
        } catch (final IOException e) {
            logger.error("Profile query error", e);
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Profile query error: " + e.getMessage()));
        }
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

    private McpSchema.CallToolResult startCrawl(final Map<String, Object> args) {
        final StartCrawlRequest request = StartCrawlRequest.fromMap(args);

        logger.info("Start crawl request: fullReindex={}", request.fullReindex());

        try {
            crawlerService.startCrawl(request.effectiveFullReindex());
            logger.info("Crawl started with fullReindex={}", request.effectiveFullReindex());

            return ToolResultHelper.createResult(StartCrawlResponse.success(request.effectiveFullReindex()));

        } catch (final IOException e) {
            logger.error("Error starting crawl", e);
            return ToolResultHelper.createResult(StartCrawlResponse.error("Error starting crawl: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getCrawlerStats() {
        logger.info("Crawler stats request");

        try {
            final CrawlStatistics stats = crawlerService.getStatistics();
            final var lastCrawlState = configManager.loadCrawlState();

            logger.info("Crawler stats: processed={}, indexed={}, failed={}",
                    stats.filesProcessed(), stats.filesIndexed(), stats.filesFailed());

            return ToolResultHelper.createResult(CrawlerStatsResponse.success(stats, lastCrawlState));

        } catch (final Exception e) {
            logger.error("Error getting crawler stats", e);
            return ToolResultHelper.createResult(CrawlerStatsResponse.error("Error getting crawler stats: " + e.getMessage()));
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

    private McpSchema.CallToolResult suggestTerms(final Map<String, Object> args) {
        final SuggestTermsRequest request = SuggestTermsRequest.fromMap(args);

        logger.info("Suggest terms request: field='{}', prefix='{}', limit={}", request.field(), request.prefix(), request.effectiveLimit());

        // Validate field
        final String fieldError = indexService.validateTermField(request.field());
        if (fieldError != null) {
            logger.warn("Invalid field for suggestTerms: {}", fieldError);
            return ToolResultHelper.createResult(SuggestTermsResponse.error(fieldError));
        }

        // Validate prefix
        if (request.prefix() == null || request.prefix().isBlank()) {
            logger.warn("Prefix is required for suggestTerms");
            return ToolResultHelper.createResult(SuggestTermsResponse.error("Prefix is required"));
        }

        try {
            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms(
                    request.field(), request.prefix(), request.effectiveLimit());

            final List<SuggestTermsResponse.TermFrequency> terms = result.terms().stream()
                    .map(e -> new SuggestTermsResponse.TermFrequency(e.getKey(), e.getValue()))
                    .toList();

            logger.info("Suggest terms completed: {} terms returned, {} total matched", terms.size(), result.totalMatched());

            return ToolResultHelper.createResult(SuggestTermsResponse.success(
                    request.field(), request.prefix(), terms, result.totalMatched()));

        } catch (final IOException e) {
            logger.error("Error suggesting terms", e);
            return ToolResultHelper.createResult(SuggestTermsResponse.error("Error suggesting terms: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getTopTerms(final Map<String, Object> args) {
        final GetTopTermsRequest request = GetTopTermsRequest.fromMap(args);

        logger.info("Get top terms request: field='{}', limit={}", request.field(), request.effectiveLimit());

        // Validate field
        final String fieldError = indexService.validateTermField(request.field());
        if (fieldError != null) {
            logger.warn("Invalid field for getTopTerms: {}", fieldError);
            return ToolResultHelper.createResult(GetTopTermsResponse.error(fieldError));
        }

        try {
            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms(
                    request.field(), request.effectiveLimit());

            final List<GetTopTermsResponse.TermFrequency> terms = result.terms().stream()
                    .map(e -> new GetTopTermsResponse.TermFrequency(e.getKey(), e.getValue()))
                    .toList();

            logger.info("Get top terms completed: {} terms returned, {} unique terms in field", terms.size(), result.uniqueTermCount());

            return ToolResultHelper.createResult(GetTopTermsResponse.success(
                    request.field(), terms, result.uniqueTermCount(), result.warning()));

        } catch (final IOException e) {
            logger.error("Error getting top terms", e);
            return ToolResultHelper.createResult(GetTopTermsResponse.error("Error getting top terms: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult pauseCrawler() {
        logger.info("Pause crawler request");

        try {
            crawlerService.pauseCrawler();
            logger.info("Crawler paused");

            return ToolResultHelper.createResult(SimpleMessageResponse.success("Crawler paused"));

        } catch (final Exception e) {
            logger.error("Error pausing crawler", e);
            return ToolResultHelper.createResult(SimpleMessageResponse.error("Error pausing crawler: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult resumeCrawler() {
        logger.info("Resume crawler request");

        try {
            crawlerService.resumeCrawler();
            logger.info("Crawler resumed");

            return ToolResultHelper.createResult(SimpleMessageResponse.success("Crawler resumed"));

        } catch (final Exception e) {
            logger.error("Error resuming crawler", e);
            return ToolResultHelper.createResult(SimpleMessageResponse.error("Error resuming crawler: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getCrawlerStatus() {
        logger.info("Crawler status request");

        try {
            final DocumentCrawlerService.CrawlerState state = crawlerService.getState();

            logger.info("Crawler state: {}", state);

            return ToolResultHelper.createResult(CrawlerStatusResponse.success(state.name()));

        } catch (final Exception e) {
            logger.error("Error getting crawler status", e);
            return ToolResultHelper.createResult(CrawlerStatusResponse.error("Error getting crawler status: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult listCrawlableDirectories() {
        logger.info("List crawlable directories request");

        try {
            final List<String> directories = configManager.loadDirectories();
            final boolean envOverride = configManager.isEnvironmentOverrideActive();
            final String configPath = configManager.getConfigPath().toString();

            logger.info("Listed {} directories (envOverride={})", directories.size(), envOverride);

            return ToolResultHelper.createResult(ListDirectoriesResponse.success(directories, configPath, envOverride));

        } catch (final Exception e) {
            logger.error("Error listing crawlable directories", e);
            return ToolResultHelper.createResult(ListDirectoriesResponse.error("Error listing crawlable directories: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult addCrawlableDirectory(final Map<String, Object> args) {
        final AddDirectoryRequest request = AddDirectoryRequest.fromMap(args);

        logger.info("Add crawlable directory request: path='{}', crawlNow={}", request.path(), request.crawlNow());

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                logger.warn("Cannot add directory - environment variable override is active");
                return ToolResultHelper.createResult(AddDirectoryResponse.error(
                        "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set"));
            }

            // Validate directory exists and is a directory
            final Path dirPath = Paths.get(request.path());
            if (!Files.exists(dirPath)) {
                logger.warn("Directory does not exist: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.error("Directory does not exist: " + request.path()));
            }

            if (!Files.isDirectory(dirPath)) {
                logger.warn("Path is not a directory: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.error("Path is not a directory: " + request.path()));
            }

            // Get current directories to check for duplicates
            final List<String> currentDirectories = configManager.loadDirectories();
            if (currentDirectories.contains(request.path())) {
                logger.info("Directory already configured: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.success(
                        "Directory already configured: " + request.path(), currentDirectories, false));
            }

            // Add to configuration
            configManager.addDirectory(request.path());

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            logger.info("Added directory: {} (total: {})", request.path(), updatedDirectories.size());

            // Optionally trigger immediate crawl
            boolean crawlStarted = false;
            if (request.effectiveCrawlNow()) {
                logger.info("Starting immediate crawl for new directory");
                crawlerService.startCrawl(false);
                crawlStarted = true;
            }

            return ToolResultHelper.createResult(AddDirectoryResponse.success(
                    "Directory added successfully: " + request.path(), updatedDirectories, crawlStarted));

        } catch (final IOException e) {
            logger.error("Error adding crawlable directory", e);
            return ToolResultHelper.createResult(AddDirectoryResponse.error("Error adding directory: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error adding crawlable directory", e);
            return ToolResultHelper.createResult(AddDirectoryResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult removeCrawlableDirectory(final Map<String, Object> args) {
        final RemoveDirectoryRequest request = RemoveDirectoryRequest.fromMap(args);

        logger.info("Remove crawlable directory request: path='{}'", request.path());

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                logger.warn("Cannot remove directory - environment variable override is active");
                return ToolResultHelper.createResult(RemoveDirectoryResponse.error(
                        "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set"));
            }

            // Check if directory exists in configuration
            final List<String> currentDirectories = configManager.loadDirectories();
            if (!currentDirectories.contains(request.path())) {
                logger.warn("Directory not found in configuration: {}", request.path());
                return ToolResultHelper.createResult(RemoveDirectoryResponse.notFound(request.path(), currentDirectories));
            }

            // Remove from configuration
            configManager.removeDirectory(request.path());

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            logger.info("Removed directory: {} (remaining: {})", request.path(), updatedDirectories.size());

            return ToolResultHelper.createResult(RemoveDirectoryResponse.success(
                    "Directory removed successfully: " + request.path(), updatedDirectories));

        } catch (final IOException e) {
            logger.error("Error removing crawlable directory", e);
            return ToolResultHelper.createResult(RemoveDirectoryResponse.error("Error removing directory: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error removing crawlable directory", e);
            return ToolResultHelper.createResult(RemoveDirectoryResponse.error("Unexpected error: " + e.getMessage()));
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

    // ==================== Index Administration Tools ====================

    private McpSchema.CallToolResult unlockIndex(final Map<String, Object> args) {
        final UnlockIndexRequest request = UnlockIndexRequest.fromMap(args);

        logger.info("Unlock index request: confirm={}", request.confirm());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Unlock index request not confirmed");
            return ToolResultHelper.createResult(UnlockIndexResponse.notConfirmed());
        }

        try {
            final boolean lockFileExisted = indexService.isLockFilePresent();
            final String lockFilePath = indexService.getLockFilePath().toString();

            if (!lockFileExisted) {
                logger.info("No lock file present at: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "No lock file present. Index is not locked.", false, lockFilePath));
            }

            final boolean removed = indexService.removeLockFile();

            if (removed) {
                logger.warn("Lock file removed: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "Lock file removed successfully. WARNING: Ensure no other process was using the index.",
                        true, lockFilePath));
            } else {
                return ToolResultHelper.createResult(UnlockIndexResponse.error(
                        "Failed to remove lock file. It may have been removed by another process."));
            }

        } catch (final IOException e) {
            logger.error("Error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Error unlocking index: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult optimizeIndex(final Map<String, Object> args) {
        final OptimizeIndexRequest request = OptimizeIndexRequest.fromMap(args);

        logger.info("Optimize index request: maxSegments={}", request.maxSegments());

        try {
            // Check if crawler is active
            final DocumentCrawlerService.CrawlerState crawlerState = crawlerService.getState();
            if (crawlerState == DocumentCrawlerService.CrawlerState.CRAWLING) {
                logger.warn("Cannot optimize while crawler is active");
                return ToolResultHelper.createResult(OptimizeIndexResponse.crawlerActive());
            }

            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot optimize the index", status.state());
                return ToolResultHelper.createResult(OptimizeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Get current segment count
            final int currentSegments = indexService.getSegmentCount();
            final int targetSegments = request.effectiveMaxSegments();

            // Start optimization
            final String operationId = indexService.startOptimization(targetSegments);

            if (operationId == null) {
                logger.warn("Failed to start optimization - another operation may have started");
                return ToolResultHelper.createResult(OptimizeIndexResponse.error(
                        "Failed to start optimization. Another operation may have started."));
            }

            logger.info("Optimization started: operationId={}, currentSegments={}, targetSegments={}",
                    operationId, currentSegments, targetSegments);

            return ToolResultHelper.createResult(OptimizeIndexResponse.started(
                    operationId, targetSegments, currentSegments));

        } catch (final IOException e) {
            logger.error("Error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Error starting optimization: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult purgeIndex(final Map<String, Object> args) {
        final PurgeIndexRequest request = PurgeIndexRequest.fromMap(args);

        logger.info("Purge index request: confirm={}, fullPurge={}", request.confirm(), request.fullPurge());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Purge index request not confirmed");
            return ToolResultHelper.createResult(PurgeIndexResponse.notConfirmed());
        }

        try {
            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot purge the index", status.state());
                return ToolResultHelper.createResult(PurgeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Start purge
            final LuceneIndexService.PurgeResult result = indexService.startPurge(request.effectiveFullPurge());

            if (result == null) {
                logger.warn("Failed to start purge - another operation may have started");
                return ToolResultHelper.createResult(PurgeIndexResponse.error(
                        "Failed to start purge. Another operation may have started."));
            }

            logger.info("Purge started: operationId={}, documentsToDelete={}, fullPurge={}",
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge());

            return ToolResultHelper.createResult(PurgeIndexResponse.started(
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge()));

        } catch (final Exception e) {
            logger.error("Error starting purge", e);
            return ToolResultHelper.createResult(PurgeIndexResponse.error("Error starting purge: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getIndexAdminStatus() {
        logger.info("Get index admin status request");

        try {
            final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();

            logger.info("Index admin status: state={}, operationId={}, progress={}%",
                    status.state(), status.operationId(), status.progressPercent());

            if (status.state() == LuceneIndexService.AdminOperationState.IDLE) {
                return ToolResultHelper.createResult(IndexAdminStatusResponse.idle(status.lastOperationResult()));
            }

            return ToolResultHelper.createResult(IndexAdminStatusResponse.success(
                    status.state().name(),
                    status.operationId(),
                    status.progressPercent(),
                    status.progressMessage(),
                    status.elapsedTimeMs(),
                    status.lastOperationResult()
            ));

        } catch (final Exception e) {
            logger.error("Error getting index admin status", e);
            return ToolResultHelper.createResult(IndexAdminStatusResponse.error("Error getting status: " + e.getMessage()));
        }
    }

}
