package de.mirkosertic.mcp.luceneserver;

import com.google.common.io.Resources;
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

    private static final String SEARCH_DESCRIPTION = """
            Search the Lucene fulltext index using LEXICAL matching (exact word forms only). \
            IMPORTANT: No automatic synonym expansion, phonetic matching, or stemming is applied. \
            To find synonyms/variants, YOU MUST explicitly include them using OR: '(contract OR agreement OR deal)'. \
            Use wildcards for variations: 'contract*' matches 'contracts', 'contracting'. \
            Supports Lucene query syntax: \
            - Simple terms: 'hello world' (implicit AND) \
            - Phrases: '"exact phrase"' \
            - Boolean: 'term1 AND term2', 'term1 OR term2', 'NOT term' \
            - Wildcards: 'test*' (suffix), 'te?t' (single char), '*test' (prefix, slow!) \
            - Field search: 'title:hello content:world' \
            - Grouping: '(contract OR agreement) AND signed' \
            Best practices: Combine related terms with OR, use wildcards for inflections, leverage facets for drill-down. \
            Returns: paginated results with snippets (highlighted matches), relevance scores, facets (with counts), and searchTimeMs.""";

    private static final String ADMIN_APP_RESOURCE_ID = "ui://indexadmin/index.html";

    private final LuceneIndexService indexService;
    private final DocumentCrawlerService crawlerService;
    private final CrawlerConfigurationManager configManager;

    public LuceneSearchTools(final LuceneIndexService indexService,
                             final DocumentCrawlerService crawlerService,
                             final CrawlerConfigurationManager configManager) {
        this.indexService = indexService;
        this.crawlerService = crawlerService;
        this.configManager = configManager;
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

        // Get index stats tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexStats")
                        .description("Get statistics about the Lucene index, including the total number of indexed documents. DO NOT call this directly after an directory was added or removed to or from the index.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexStats())
                .build());

        // Start crawl tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("startCrawl")
                        .description("Start crawling configured directories to index documents. " +
                                "Supports MSOffice, OpenOffice, and PDF files. Automatically detects document language and extracts metadata.")
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

        logger.info("Search request: query='{}', filterField='{}', filterValue='{}', page={}, pageSize={}",
                request.query(), request.filterField(), request.filterValue(), request.page(), request.pageSize());

        try {
            final long startTime = System.nanoTime();
            final LuceneIndexService.SearchResult result = indexService.search(
                    request.query(),
                    request.filterField(),
                    request.filterValue(),
                    request.effectivePage(),
                    request.effectivePageSize());
            final long durationMs = (System.nanoTime() - startTime) / 1_000_000;

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
                    durationMs
            );

            logger.info("Search completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions available",
                    durationMs, result.totalHits(), result.page(), result.totalPages(), result.facets().size());

            return ToolResultHelper.createResult(response);

        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error("Invalid query syntax: " + e.getMessage()));
        } catch (final IOException e) {
            logger.error("Search error", e);
            return ToolResultHelper.createResult(SearchResponse.error("Search error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getIndexStats() {
        logger.info("Index stats request");

        try {
            final long documentCount = indexService.getDocumentCount();
            final String indexPath = indexService.getIndexPath();

            logger.info("Index stats: {} documents", documentCount);

            return ToolResultHelper.createResult(IndexStatsResponse.success(documentCount, indexPath));

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
                logger.warn("Another admin operation is running: {}", status.state());
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
                logger.warn("Another admin operation is running: {}", status.state());
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
